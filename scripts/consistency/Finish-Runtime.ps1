[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaPath)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/consistency-evidence'
$docker='C:\Program Files\Docker\Docker\resources\bin\docker.exe'
function Sql([string]$Query){
    $output=$Query|& $docker exec -i omnidoc-baseline-20261014-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names omnidoc'
    if($LASTEXITCODE -ne 0){throw 'Final read-only SQL evidence query failed'}
    return $output
}
function Redis([string[]]$Arguments){
    $output=& $docker exec omnidoc-baseline-20261014-redis-1 sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli -n 11 --raw "$@"' sh @Arguments
    if($LASTEXITCODE -ne 0){throw 'Final scoped Redis operation failed'}
    return ($output -join "`n")
}
$ui=Get-Content (Join-Path $evidence 'runtime-fixtures.json') -Raw|ConvertFrom-Json
$fault=Get-Content (Join-Path $evidence 'reliability-fixture.json') -Raw|ConvertFrom-Json
if($ui.fixtureKind -ne 'CONSISTENCY_REAL_WORKER_SYNTHETIC_MODELS' -or $fault.kind -ne 'REAL_WORKER_RELIABILITY'){throw 'Unexpected fixture identity'}
$ids=@($ui.documentId,$fault.documentId)
foreach($id in $ids){if($id -notmatch '^[a-zA-Z0-9_-]+$'){throw 'Unexpected document identifier'}}
$inList=($ids|ForEach-Object {"'$_'"}) -join ','
$deleted=[int](Sql "SELECT COUNT(*) FROM document WHERE id IN ($inList) AND status='deleted';")
$pending=[int](Sql "SELECT COUNT(*) FROM document_index_task WHERE document_id IN ($inList) AND operation='CLEANUP' AND state<>'SUCCEEDED';")
if($deleted -ne 2 -or $pending -ne 0){throw 'Both isolated documents must be deleted and worker cleanup complete before finalization'}
& (Join-Path $PSScriptRoot 'Stop-Runtime.ps1') -Services ai-service,document-service
$generations=[Collections.Generic.HashSet[string]]::new()
foreach($line in (Sql "SELECT JSON_OBJECT('generationId',generation_id,'cleanupIds',cleanup_generation_ids) FROM document_index_task WHERE document_id IN ($inList);")){
    $row=$line|ConvertFrom-Json
    if($row.generationId){$null=$generations.Add($row.generationId)}
    if($row.cleanupIds){foreach($generation in ($row.cleanupIds|ConvertFrom-Json)){$null=$generations.Add($generation)}}
}
$checks=[Collections.Generic.List[object]]::new()
foreach($generation in $generations){
    if($generation -notmatch '^[a-zA-Z0-9_-]+$'){throw 'Unexpected generation identifier'}
    $key='index:v1:generation:'+$generation
    $manifestExists=[int](Redis -Arguments @('EXISTS',$key))
    $raw=Redis -Arguments @('GET',($key+':retired'))
    $vectorKeys=0
    if($raw){
        $tombstone=$raw|ConvertFrom-Json
        if($tombstone.documentId -notin $ids -or $tombstone.generationId -ne $generation){throw 'Tombstone scope differs from fixture identity'}
        foreach($segment in $tombstone.segmentIds){$vectorKeys += [int](Redis -Arguments @('EXISTS',('vector:'+$segment),('metadata:'+$segment)))}
        if($manifestExists -ne 0 -or $vectorKeys -ne 0){throw 'Real worker left fixture manifest/vector keys; do not hide failure with manual deletion'}
        $null=Redis -Arguments @('DEL',($key+':retired'))
        $null=Redis -Arguments @('SREM','index:v1:tombstones',$generation)
    }elseif($manifestExists -ne 0){throw 'Unreclaimed fixture manifest without tombstone'}
    $checks.Add(@{generationId=$generation;manifestKeys=$manifestExists;vectorMetadataKeys=$vectorKeys;tombstoneRemoved=[bool]$raw})
}
& (Join-Path $PSScriptRoot 'Start-Runtime.ps1') -JavaPath $JavaPath -Services document-service,ai-service -Mode Disabled
& (Join-Path $PSScriptRoot 'Wait-Runtime.ps1') -Ports 8080,8081,8082,8083,8084 -TimeoutSeconds 60
$private=Get-Content (Join-Path $repo '.local/baseline-runtime/test-session.json') -Raw|ConvertFrom-Json
$login=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/users/login -ContentType application/json -Body (@{username=$private.account.username;password=$private.account.password}|ConvertTo-Json)
if(-not $login.data.accessToken){throw 'Final ordinary-mode login failed'}
$request=Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/ai/rag/search?query=final-check' -Headers @{Authorization='Bearer '+$login.data.accessToken} -SkipHttpErrorCheck
if($request.StatusCode -ne 409 -or $request.Content -notmatch 'EMBEDDING_DISABLED|INDEX_NOT_READY'){throw 'Final ordinary-mode AI boundary unexpected'}
$processes=@(Get-Content (Join-Path $evidence 'runtime-processes.json') -Raw|ConvertFrom-Json)|Group-Object service|ForEach-Object {$_.Group|Sort-Object startedAtUtc -Descending|Select-Object -First 1}
if(@($processes|Where-Object mode -ne 'Disabled').Count){throw 'A synthetic service remains in latest runtime manifest'}
$summary=@{passed=$true;finishedAtUtc=[DateTime]::UtcNow.ToString('o');fixtureDocumentIds=$ids;deletedFixtures=$deleted;pendingCleanup=$pending;generations=@($checks);retainedLegacyVersions=[int](Sql 'SELECT COUNT(*) FROM document_version WHERE verified_version IS NULL;');activeDocuments=[int](Sql "SELECT COUNT(*) FROM document WHERE status='active';");ordinaryMode='Disabled production jars, no test classpath';finalSearchHttp=[int]$request.StatusCode;finalSearchBody=($request.Content|ConvertFrom-Json);services=$processes;originalFilesAndImmutableVersionsPreserved=$true}
$summary|ConvertTo-Json -Depth 12|Set-Content (Join-Path $evidence 'runtime-final-state.json') -Encoding utf8
Write-Output "Finalized $($generations.Count) fixture generations; ordinary Disabled mode functional; originals and history retained."
