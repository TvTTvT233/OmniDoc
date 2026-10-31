[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaPath,[Parameter(Mandatory)][string]$DependencyClasspathFile)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/consistency-evidence'
$manifest=Join-Path $evidence 'reliability-fixture.json'
if(Test-Path -LiteralPath $manifest){throw 'Reliability fixture already exists; inspect prior evidence before resuming, do not duplicate.'}
$private=Get-Content (Join-Path $repo '.local/baseline-runtime/test-session.json') -Raw|ConvertFrom-Json
$login=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/users/login -ContentType 'application/json' -Body (@{username=$private.account.username;password=$private.account.password}|ConvertTo-Json)
$token=$login.data.accessToken
if(-not $token){throw 'Synthetic owner login failed'}
$checks=[Collections.Generic.List[object]]::new()
$fixture=@{kind='REAL_WORKER_RELIABILITY';createdAtUtc=[DateTime]::UtcNow.ToString('o');redisDatabase=11}
$status='FAILED';$failure=$null
function Api([string]$Method,[string]$Path,[object]$Body){
    $p=@{
        Method=$Method
        Uri='http://127.0.0.1:8080'+$Path
        Headers=@{Authorization='Bearer '+$token}
        SkipHttpErrorCheck=$true
        TimeoutSec=30
    }
    if($null -ne $Body){$p.ContentType='application/json; charset=utf-8';$p.Body=$Body|ConvertTo-Json -Depth 10 -Compress}
    $r=Invoke-WebRequest @p;return @{http=[int]$r.StatusCode;body=($r.Content|ConvertFrom-Json)}
}
function Check([string]$Name,[bool]$Pass,[object]$Observed){
    $checks.Add(@{name=$Name;passed=$Pass;observed=$Observed})
    @{status='RUNNING';checks=@($checks)}|ConvertTo-Json -Depth 12|Set-Content (Join-Path $evidence 'reliability-progress.json') -Encoding utf8
    if(-not $Pass){throw "Reliability assertion failed: $Name"}
    Write-Output "PASS $Name"
}
function Sql([string]$Query){
    $output=$Query|& 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' exec -i omnidoc-baseline-20261014-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names omnidoc'
    if($LASTEXITCODE -ne 0){throw 'Read-only evidence query failed'}
    return ($output -join "`n")
}
function Redis([string[]]$Arguments,[string]$InputValue){
    $docker='C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $command='REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli -n 11 --raw "$@"'
    if($PSBoundParameters.ContainsKey('InputValue')) {
        $output=$InputValue|& $docker exec -i omnidoc-baseline-20261014-redis-1 sh -c $command sh @Arguments
    } else { $output=& $docker exec omnidoc-baseline-20261014-redis-1 sh -c $command sh @Arguments }
    if($LASTEXITCODE -ne 0){throw 'Scoped Redis evidence operation failed'}
    return ($output -join "`n")
}
function WaitState([string[]]$States,[int]$Seconds=120){
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do {$r=Api GET "/api/documents/$($fixture.documentId)/index-state" $null;if($r.http -ne 200){throw 'Index-state read failed'};$s=$r.body.data;if($s.indexState -in $States){return $s};Start-Sleep -Milliseconds 300}while([DateTime]::UtcNow -lt $deadline)
    throw "Timed out; indexState=$($s.indexState), errorCode=$($s.errorCode)"
}
function RestartAI([string]$Mode='Synthetic',[int]$Delay=0,[int]$FailAt=-1){
    & (Join-Path $PSScriptRoot 'Stop-Runtime.ps1') -Services ai-service
    & (Join-Path $PSScriptRoot 'Start-Runtime.ps1') -JavaPath $JavaPath -Services ai-service -Mode $Mode -DependencyClasspathFile $DependencyClasspathFile -EmbeddingDelayMs $Delay -EmbeddingFailAt $FailAt
    & (Join-Path $PSScriptRoot 'Wait-Runtime.ps1') -Ports 8083 -TimeoutSeconds 60
}
function Save([int]$Version,[string]$Content){
    $r=Api PUT "/api/documents/$($fixture.documentId)" @{expectedVersion=$Version;contentFormat='PLAIN_TEXT';content=$Content;changeLog='Synthetic reliability evidence'}
    Check "save_revision_$($Version+1)" ($r.http -eq 200 -and $r.body.data.version -eq ($Version+1)) @{http=$r.http;version=$r.body.data.version}
    return $r.body.data
}
try {
    RestartAI -Delay 1000 -FailAt 2
    $r=Api POST '/api/documents' @{title='Synthetic worker reliability (isolated test)'}
    Check 'create_empty_test_document' ($r.http -eq 200 -and $r.body.data.version -eq 1) @{http=$r.http}
    $fixture.documentId=$r.body.data.id;$fixture|ConvertTo-Json|Set-Content $manifest -Encoding utf8
    $id=$fixture.documentId
    if($id -notmatch '^[a-zA-Z0-9_-]+$'){throw 'Unexpected document identifier'}
    $long=('Synthetic controlled paragraph 😀. '*45)
    $null=Save 1 $long
    $s=WaitState @('READY','FAILED')
    Check 'partial_failure_retried_by_real_worker' ($s.indexState -eq 'READY' -and $s.queryable) @{state=$s.indexState;version=$s.currentVersion}
    $attempt=[int](Sql "SELECT attempt FROM document_index_task WHERE document_id='$id' AND document_version=2 AND operation='BUILD';")
    Check 'partial_failure_used_second_attempt' ($attempt -eq 2) @{attempt=$attempt}
    $failedCount=[int](Sql "SELECT COUNT(*) FROM document_index_task WHERE document_id='$id' AND operation='CLEANUP' AND state='SUCCEEDED';")
    Check 'failed_generation_cleanup_acknowledged' ($failedCount -ge 1) @{cleanupSucceeded=$failedCount}

    RestartAI -Delay 4000
    $null=Save 2 ($long+'old build')
    $old=WaitState @('BUILDING')
    $oldTask=Sql "SELECT JSON_OBJECT('taskId',task_id,'leaseToken',lease_token,'generationId',generation_id,'contentHash',content_hash,'configHash',config_hash) FROM document_index_task WHERE task_id='$($old.taskId)';"|ConvertFrom-Json
    $null=Save 3 ($long+'new target')
    $immediate=Api GET "/api/documents/$id/index-state" $null
    Check 'save_immediately_hides_old_generation' (-not $immediate.body.data.queryable -and -not $immediate.body.data.activeGenerationId) @{state=$immediate.body.data.indexState;version=$immediate.body.data.currentVersion}
    $s=WaitState @('READY','FAILED')
    Check 'newer_revision_wins_superseded_worker' ($s.indexState -eq 'READY' -and $s.indexedVersion -eq 4 -and $s.activeGenerationId -ne $oldTask.generationId) @{state=$s.indexState;version=$s.indexedVersion}
    $registered=[int](Sql "SELECT COUNT(*) FROM document_index_reference WHERE generation_id='$($oldTask.generationId)';")
    Check 'superseded_worker_registered_no_references' ($registered -eq 0) @{registeredReferences=$registered}
    # A negative late-completion probe uses an actual retired lease; it never fabricates a successful generation.
    $serviceToken=(Get-Content (Join-Path $repo '.local/baseline-runtime/runtime.env')|Where-Object {$_ -match '^OMNIDOC_INDEX_INTERNAL_TOKEN='}) -replace '^OMNIDOC_INDEX_INTERNAL_TOKEN=',''
    $late=Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:8084/internal/index/tasks/$($oldTask.taskId)/complete" -Headers @{'X-OmniDoc-Index-Token'=$serviceToken} -ContentType 'application/json' -Body (@{leaseToken=$oldTask.leaseToken;generationId=$oldTask.generationId;contentHash=$oldTask.contentHash;configHash=$oldTask.configHash;segments=@()}|ConvertTo-Json) -SkipHttpErrorCheck
    $lateBody=$late.Content|ConvertFrom-Json
    Check 'retired_actual_lease_cannot_publish_late' ($late.StatusCode -eq 200 -and -not $lateBody.data.published -and -not $lateBody.data.accepted) @{http=[int]$late.StatusCode;published=$lateBody.data.published;accepted=$lateBody.data.accepted}

    RestartAI -Delay 10000
    $null=Save 4 ($long+'lease recovery')
    $running=WaitState @('BUILDING')
    $abandoned=Sql "SELECT generation_id FROM document_index_task WHERE task_id='$($running.taskId)';"
    & (Join-Path $PSScriptRoot 'Stop-Runtime.ps1') -Services ai-service
    # Startup plus the expired lease determines eligibility; no database clock/task manipulation.
    & (Join-Path $PSScriptRoot 'Start-Runtime.ps1') -JavaPath $JavaPath -Services ai-service -Mode Synthetic -DependencyClasspathFile $DependencyClasspathFile
    & (Join-Path $PSScriptRoot 'Wait-Runtime.ps1') -Ports 8083 -TimeoutSeconds 60
    $s=WaitState @('READY','FAILED')
    $attempt=[int](Sql "SELECT attempt FROM document_index_task WHERE task_id='$($running.taskId)';")
    Check 'worker_restart_recovers_expired_lease_with_new_generation' ($s.indexState -eq 'READY' -and $attempt -eq 2 -and $s.activeGenerationId -ne $abandoned) @{state=$s.indexState;attempt=$attempt;version=$s.indexedVersion}

    RestartAI -Mode Disabled
    $null=Save 5 ($long+'explicit retry')
    $s=WaitState @('FAILED')
    Check 'disabled_worker_failure_has_no_queryable_fallback' ($s.errorCode -eq 'EMBEDDING_DISABLED' -and -not $s.queryable -and -not $s.activeGenerationId) @{errorCode=$s.errorCode;queryable=$s.queryable}
    $configBefore=Sql "SELECT config_hash FROM document_index_task WHERE task_id='$($s.taskId)';"
    $retryTaskId=$s.taskId
    RestartAI
    $retry=Api POST "/api/documents/$id/index/retry" @{expectedVersion=6}
    Check 'explicit_retry_accepted' ($retry.http -eq 202) @{http=$retry.http}
    $s=WaitState @('READY','FAILED')
    $configAfter=Sql "SELECT config_hash FROM document_index_task WHERE task_id='$retryTaskId';"
    Check 'explicit_retry_preserves_configuration_and_publishes' ($s.indexState -eq 'READY' -and $s.indexedVersion -eq 6 -and $configBefore -eq $configAfter) @{state=$s.indexState;version=$s.indexedVersion;configurationPreserved=($configBefore -eq $configAfter)}
    & (Join-Path $PSScriptRoot 'Stop-Runtime.ps1') -Services ai-service
    $restored=Api POST "/api/documents/$id/restore/2" @{expectedVersion=6}
    Check 'restore_creates_new_revision_with_exact_historical_body' ($restored.http -eq 200 -and $restored.body.data.version -eq 7 -and $restored.body.data.content -eq $long) @{http=$restored.http;version=$restored.body.data.version}
    $r=Api GET "/api/documents/$id/index-state" $null
    Check 'restore_immediately_invalidates_previous_generation' (-not $r.body.data.queryable -and -not $r.body.data.activeGenerationId) @{state=$r.body.data.indexState;version=$r.body.data.currentVersion}
    & (Join-Path $PSScriptRoot 'Start-Runtime.ps1') -JavaPath $JavaPath -Services ai-service -Mode Synthetic -DependencyClasspathFile $DependencyClasspathFile
    & (Join-Path $PSScriptRoot 'Wait-Runtime.ps1') -Ports 8083 -TimeoutSeconds 60
    $s=WaitState @('READY','FAILED')
    Check 'restored_revision_published_by_real_worker' ($s.indexState -eq 'READY' -and $s.indexedVersion -eq 7) @{state=$s.indexState;version=$s.indexedVersion}
    & (Join-Path $PSScriptRoot 'Stop-Runtime.ps1') -Services ai-service
    $cleanupGeneration=$s.activeGenerationId
    $manifestKey='index:v1:generation:'+$cleanupGeneration
    $manifestRaw=Redis -Arguments @('GET',$manifestKey)
    $generationManifest=$manifestRaw|ConvertFrom-Json
    if($generationManifest.documentId -ne $id -or $generationManifest.documentVersion -ne 7 -or $generationManifest.generationId -ne $cleanupGeneration){throw 'Refusing fault injection outside verified fixture generation'}
    [IO.File]::WriteAllText((Join-Path $evidence 'cleanup-manifest-backup.private.json'),$manifestRaw,[Text.UTF8Encoding]::new($false))
    $segmentIds=@($generationManifest.segments|ForEach-Object {$_.source.segmentId})
    $delete=Api DELETE "/api/documents/$id" $null
    Check 'reliability_fixture_soft_deleted' ($delete.http -eq 200) @{http=$delete.http}
    $faultRestored=$false
    try {
        $null=Redis -Arguments @('SET',$manifestKey,'controlled-invalid-json-for-cleanup-recovery')
        & (Join-Path $PSScriptRoot 'Start-Runtime.ps1') -JavaPath $JavaPath -Services ai-service -Mode Synthetic -DependencyClasspathFile $DependencyClasspathFile
        & (Join-Path $PSScriptRoot 'Wait-Runtime.ps1') -Ports 8083 -TimeoutSeconds 60
        $deadline=[DateTime]::UtcNow.AddSeconds(30)
        do {$failed=[int](Sql "SELECT COUNT(*) FROM document_index_task WHERE document_id='$id' AND idempotency_key='CLEANUP:$cleanupGeneration' AND error_code='INDEX_STORAGE_UNAVAILABLE' AND attempt>=1;");if($failed -gt 0){break};Start-Sleep -Milliseconds 250}while([DateTime]::UtcNow -lt $deadline)
        Check 'controlled_manifest_fault_is_recorded_as_cleanup_failure' ($failed -eq 1) @{generationId=$cleanupGeneration;failedTasks=$failed;fault='Only isolated DB11 fixture manifest replaced with invalid JSON'}
    } finally {
        $null=Redis -Arguments @('-x','SET',$manifestKey) -InputValue $manifestRaw
        $faultRestored=$true
    }
    Check 'controlled_manifest_fault_restored' $faultRestored @{generationId=$cleanupGeneration}
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do {$pending=[int](Sql "SELECT COUNT(*) FROM document_index_task WHERE document_id='$id' AND operation='CLEANUP' AND state<>'SUCCEEDED';");if($pending -eq 0){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $deadline)
    Check 'all_reliability_generations_cleaned_by_worker' ($pending -eq 0) @{pendingCleanup=$pending}
    $cleanupAttempts=[int](Sql "SELECT attempt FROM document_index_task WHERE document_id='$id' AND idempotency_key='CLEANUP:$cleanupGeneration';")
    Check 'cleanup_recovers_by_real_worker_retry' ($cleanupAttempts -ge 2) @{attempt=$cleanupAttempts}
    $remaining=[int](Redis -Arguments @('EXISTS',$manifestKey))
    foreach($segmentId in $segmentIds){$remaining += [int](Redis -Arguments @('EXISTS',('vector:'+$segmentId),('metadata:'+$segmentId)))}
    Check 'deleted_generation_manifest_and_vectors_absent' ($remaining -eq 0) @{remainingKeys=$remaining;segmentCount=$segmentIds.Count}
    $status='PASSED'
}catch {$failure=$_.Exception.Message;throw}finally {
    @{status=$status;checks=@($checks);error=$failure;generatedAtUtc=[DateTime]::UtcNow.ToString('o');fixture=$fixture;externalModelCalls=0;modelBoundary='Explicit test-only deterministic embedding/chat; production worker and real HTTP/MySQL/Redis/VectorStore';lateCompleteBoundary='Negative probe of an actual retired worker lease only; all successful publications performed by real worker'}|ConvertTo-Json -Depth 12|Set-Content (Join-Path $evidence ('worker-reliability-'+(Get-Date -Format yyyyMMdd-HHmmss)+'.json')) -Encoding utf8
}
