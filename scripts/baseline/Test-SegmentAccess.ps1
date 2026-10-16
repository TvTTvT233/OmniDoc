[CmdletBinding()]
param([ValidateSet('Normal','DocumentServiceUnavailable','RedisUnavailable')][string]$Mode='Normal')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$dir=Join-Path $repo '.local/baseline-evidence/segment-access-2026-10-16'
$fixtures=Get-Content (Join-Path $dir 'fixtures.json') -Raw|ConvertFrom-Json
$owner=Get-Content (Join-Path $repo '.local/baseline-runtime/test-session.json') -Raw|ConvertFrom-Json
$other=Get-Content (Join-Path $dir 'other-session.private.json') -Raw|ConvertFrom-Json
$checks=[Collections.Generic.List[object]]::new()
function Login($Session){
    $r=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/users/login -ContentType application/json -Body (@{username=$Session.account.username;password=$Session.account.password}|ConvertTo-Json)
    if($r.code -ne 200){throw 'Synthetic account login failed'}
    return @{Authorization='Bearer '+$r.data.accessToken}
}
$ownerHeaders=Login $owner
$otherHeaders=Login $other
function Request([string]$Url,[hashtable]$Headers=@{}){
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $r=Invoke-WebRequest -Uri $Url -Headers $Headers -SkipHttpErrorCheck -TimeoutSec 35
    $body=$null
    try{$body=$r.Content|ConvertFrom-Json}catch{}
    return [pscustomobject]@{http=[int]$r.StatusCode;body=$body;elapsedMs=$watch.ElapsedMilliseconds}
}
function Check([string]$Name,$Response,[bool]$Passed){
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed;http=$Response.http;code=$Response.body.code;indexState=$Response.body.data.indexState;elapsedMs=$Response.elapsedMs})
    if(-not $Passed){throw "Check failed: $Name"}
}
function Segment([string]$Id,[hashtable]$Headers){Request "http://127.0.0.1:8080/api/ai/rag/document/$Id/segments" $Headers}
function Redis([string[]]$Arguments){
    $r=& docker exec omnidoc-baseline-20261014-redis-1 sh -c 'export REDISCLI_AUTH=$REDIS_PASSWORD; exec redis-cli -n 1 "$@"' sh @Arguments
    if($LASTEXITCODE -ne 0){throw 'Fixture Redis operation failed'}
    return $r
}
function RestoreJson([string]$Key,[string]$Json){
    $Json|& docker exec -i omnidoc-baseline-20261014-redis-1 sh -c 'export REDISCLI_AUTH=$REDIS_PASSWORD; exec redis-cli -n 1 -x SET "$1"' sh $Key|Out-Null
    if($LASTEXITCODE -ne 0){throw 'Fixture restore failed'}
    Redis @('EXPIRE',$Key,'86400')|Out-Null
}
$failure=$null
try{
    if($Mode -eq 'Normal'){
        $r=Segment $fixtures.unindexedDocumentId $ownerHeaders
        Check 'owner_unindexed_200_explicit_empty' $r ($r.http -eq 200 -and $r.body.code -eq 200 -and $r.body.data.indexState -eq 'NOT_INDEXED' -and @($r.body.data.segments).Count -eq 0)
        $r=Segment $fixtures.indexedDocumentId $ownerHeaders
        Check 'owner_synthetic_segments_available_exact_contents' $r ($r.http -eq 200 -and $r.body.data.indexState -eq 'SEGMENTS_AVAILABLE' -and @($r.body.data.segments).Count -eq 2 -and (($r.body.data.segments.content -join '|') -eq ($fixtures.expectedContents -join '|')))
        $r=Segment $fixtures.unindexedDocumentId $otherHeaders
        Check 'other_user_unindexed_document_denied_404' $r ($r.http -eq 404 -and $null -eq $r.body.data)
        $r=Segment $fixtures.indexedDocumentId $otherHeaders
        Check 'other_user_segment_content_denied_404' $r ($r.http -eq 404 -and $null -eq $r.body.data)
        $r=Segment 'segment-access-nonexistent-document' $ownerHeaders
        Check 'missing_document_denied_404' $r ($r.http -eq 404 -and $null -eq $r.body.data)
        $r=Segment $fixtures.unindexedDocumentId @{}
        Check 'unauthenticated_gateway_denied_401' $r ($r.http -eq 401)
        $r=Request "http://127.0.0.1:8083/api/ai/rag/document/$($fixtures.unindexedDocumentId)/segments" @{'X-User-Id'=[string]$fixtures.ownerId}
        Check 'direct_ai_spoofed_header_without_jwt_denied_401' $r ($r.http -eq 401)
        $r=Request "http://127.0.0.1:8084/api/documents/$($fixtures.unindexedDocumentId)/read-access" @{'X-User-Id'=[string]$fixtures.ownerId}
        Check 'direct_document_spoofed_header_without_jwt_denied_401' $r ($r.http -eq 401)
        $spoofed=@{Authorization=$otherHeaders.Authorization;'X-User-Id'=[string]$fixtures.ownerId}
        $r=Request "http://127.0.0.1:8084/api/documents/$($fixtures.unindexedDocumentId)/read-access" $spoofed
        Check 'direct_document_other_jwt_with_owner_header_denied_404' $r ($r.http -eq 404)
        $r=Request "http://127.0.0.1:8084/api/documents/$($fixtures.unindexedDocumentId)/read-access" $ownerHeaders
        Check 'direct_document_owner_access_200' $r ($r.http -eq 200)
        $metaKey="doc:$($fixtures.indexedDocumentId)"
        $savedOwner=Redis @('HGET',$metaKey,'userId')
        try{
            $otherJson=ConvertTo-Json -InputObject ([string]$fixtures.otherUserId) -Compress
            $otherJson|& docker exec -i omnidoc-baseline-20261014-redis-1 sh -c 'export REDISCLI_AUTH=$REDIS_PASSWORD; exec redis-cli -n 1 -x HSET "$1" userId' sh $metaKey|Out-Null
            if($LASTEXITCODE -ne 0){throw 'Synthetic metadata mutation failed'}
            $r=Segment $fixtures.indexedDocumentId $ownerHeaders
            Check 'trusted_document_owner_but_index_scope_mismatch_403' $r ($r.http -eq 403 -and $null -eq $r.body.data)
        }finally{
            $savedOwner|& docker exec -i omnidoc-baseline-20261014-redis-1 sh -c 'export REDISCLI_AUTH=$REDIS_PASSWORD; exec redis-cli -n 1 -x HSET "$1" userId' sh $metaKey|Out-Null
            if($LASTEXITCODE -ne 0){throw 'Synthetic metadata restore failed'}
        }
        $key="segment:$($fixtures.segmentIds[1])"
        $saved=Redis @('GET',$key)
        if(-not $saved){throw 'Synthetic segment expired or missing; refusing mutation'}
        try{
            Redis @('DEL',$key)|Out-Null
            $r=Segment $fixtures.indexedDocumentId $ownerHeaders
            Check 'missing_segment_content_is_409_not_empty_success' $r ($r.http -eq 409 -and $null -eq $r.body.data)
        }finally{RestoreJson $key $saved}
        $key="doc_segments:$($fixtures.indexedDocumentId)"
        $saved=Redis @('GET',$key)
        try{
            Redis @('DEL',$key)|Out-Null
            $r=Segment $fixtures.indexedDocumentId $ownerHeaders
            Check 'metadata_without_segment_list_is_409' $r ($r.http -eq 409 -and $null -eq $r.body.data)
        }finally{RestoreJson $key $saved}
        $r=Segment $fixtures.indexedDocumentId $ownerHeaders
        Check 'synthetic_fixture_restored_after_partial_data_checks' $r ($r.http -eq 200 -and $r.body.data.indexState -eq 'SEGMENTS_AVAILABLE' -and @($r.body.data.segments).Count -eq 2)
    }elseif($Mode -eq 'DocumentServiceUnavailable'){
        $record=@(Get-Content (Join-Path $repo '.local/baseline-runtime/processes.json') -Raw|ConvertFrom-Json)|Where-Object service -eq 'document-service'|Select-Object -Last 1
        $p=Get-Process -Id $record.processId -ErrorAction Stop
        if($p.StartTime.ToUniversalTime().Ticks -ne ([datetime]$record.startedAtUtc).ToUniversalTime().Ticks){throw 'Document process identity changed'}
        $java=$p.Path
        try{
            & (Join-Path $PSScriptRoot 'Stop-Services.ps1') -Services document-service
            $r=Segment $fixtures.unindexedDocumentId $ownerHeaders
            Check 'document_service_down_is_503_not_unindexed' $r ($r.http -eq 503 -and $null -eq $r.body.data)
        }finally{
            & (Join-Path $PSScriptRoot 'Start-Services.ps1') -JavaPath $java -Services document-service
        }
    }else{
        $name='omnidoc-baseline-20261014-redis-1'
        $project=& docker inspect $name --format '{{index .Config.Labels "com.docker.compose.project"}}'
        if($project -ne 'omnidoc-baseline-20261014'){throw 'Unexpected Redis project; refusing to stop it'}
        try{
            & docker stop $name|Out-Null
            if($LASTEXITCODE -ne 0){throw 'Redis stop failed'}
            # Direct AI path isolates dependency behavior from gateway logging/rate-limit Redis usage.
            $r=Request "http://127.0.0.1:8083/api/ai/rag/document/$($fixtures.unindexedDocumentId)/segments" $ownerHeaders
            Check 'redis_down_is_503_not_unindexed' $r ($r.http -eq 503 -and $null -eq $r.body.data)
        }finally{& docker start $name|Out-Null}
    }
}catch{$failure=$_.Exception.Message;Write-Warning $failure}
$report=@{head=(git -C $repo rev-parse HEAD);mode=$Mode;generatedAtUtc=[DateTime]::UtcNow.ToString('o');status=$(if($failure){'FAILED'}else{'PASSED'});checks=@($checks.ToArray());error=$failure;fixtureKind=$fixtures.fixtureKind;externalModelCalls=0;vectorWrites=0}
$report|ConvertTo-Json -Depth 6|Set-Content (Join-Path $dir "after-$Mode.json") -Encoding utf8
$report|ConvertTo-Json -Depth 6
if($failure){exit 1}
