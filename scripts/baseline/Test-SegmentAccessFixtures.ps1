[CmdletBinding()]
param([switch]$Cleanup)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/baseline-evidence/segment-access-2026-10-16'
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$statePath=Join-Path $evidence 'fixtures.json'
$redis='omnidoc-baseline-20261014-redis-1'
function Redis([string[]]$Arguments){
    $result=& docker exec $redis sh -c 'export REDISCLI_AUTH=$REDIS_PASSWORD; exec redis-cli -n 1 "$@"' sh @Arguments
    if($LASTEXITCODE -ne 0){throw 'Fixture Redis command failed'}
    return $result
}
if($Cleanup){
    if(-not(Test-Path $statePath)){throw 'No fixture manifest; refusing broad cleanup'}
    $f=Get-Content $statePath -Raw|ConvertFrom-Json
    if($f.fixtureKind -ne 'SYNTHETIC_REDIS_SEGMENTS_WITHOUT_EMBEDDINGS' -or $f.indexedDocumentId -eq $f.unindexedDocumentId){throw 'Unexpected fixture manifest'}
    $allowed=@("doc:$($f.indexedDocumentId)","doc_segments:$($f.indexedDocumentId)","content:$($f.indexedDocumentId)") + @($f.segmentIds|ForEach-Object {"segment:$_"})
    if($allowed.Count -ne 5 -or @($f.segmentIds|Where-Object {$_ -notmatch ('^'+[regex]::Escape($f.indexedDocumentId)+'_seg_[0-9]+$')}).Count){throw 'Unexpected fixture keys'}
    $removed=Redis (@('DEL')+$allowed)
    $remaining=Redis (@('EXISTS')+$allowed)
    @{generatedAtUtc=[DateTime]::UtcNow.ToString('o');removed=[int]$removed;remaining=[int]$remaining;documentAndFileRetained=$true}|ConvertTo-Json|Set-Content (Join-Path $evidence 'fixture-cleanup.json')
    Write-Output "Cleaned only manifest-listed synthetic Redis keys; remaining=$remaining. Synthetic document/file retained."
    exit 0
}
if(Test-Path $statePath){throw 'Existing fixture manifest found; reuse it rather than creating duplicate data'}
$s=Get-Content (Join-Path $repo '.local/baseline-runtime/test-session.json') -Raw|ConvertFrom-Json
$login=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/users/login -ContentType application/json -Body (@{username=$s.account.username;password=$s.account.password}|ConvertTo-Json)
if($login.code -ne 200){throw 'Owner login failed'}
$h=@{Authorization='Bearer '+$login.data.accessToken}
$suffix=[guid]::NewGuid().ToString('N').Substring(0,12)
$attacker=@{username="segment_other_$suffix";password=[guid]::NewGuid().ToString('N');email="segment-$suffix@example.invalid";phone=('138'+(Get-Random -Minimum 10000000 -Maximum 99999999))}
$registered=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/users/register -ContentType application/json -Body ($attacker|ConvertTo-Json)
if($registered.code -ne 200){throw 'Synthetic second account registration failed'}
@{account=$attacker;userId=$registered.data.id}|ConvertTo-Json -Depth 4|Set-Content (Join-Path $evidence 'other-session.private.json') -Encoding utf8
$sample=Join-Path $evidence "synthetic-segments-$suffix.txt"
$parts=@('Synthetic segment access fixture: section one.','Synthetic segment access fixture: section two.')
[IO.File]::WriteAllText($sample,($parts -join "`n"),[Text.UTF8Encoding]::new($false))
$upload=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/files/upload -Headers $h -Form @{file=Get-Item $sample}
if($upload.code -ne 200){throw 'Synthetic file upload failed'}
$created=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/documents -Headers $h -ContentType application/json -Body (@{title="Segment access fixture $suffix";fileId=$upload.data.fileId;category='baseline'}|ConvertTo-Json)
if($created.code -ne 200){throw 'Synthetic document create failed'}
$id=$created.data.id
if($id -notmatch '^[A-Za-z0-9-]+$'){throw 'Unexpected fixture document identifier'}
$old=Get-Content (Join-Path $repo '.local/baseline-runtime/document-fixture.json') -Raw|ConvertFrom-Json
$segmentIds=@("${id}_seg_0","${id}_seg_1")
$keys=@("doc:$id","doc_segments:$id","content:$id")+@($segmentIds|ForEach-Object {"segment:$_"})
if([int](Redis (@('EXISTS')+$keys)) -ne 0){throw 'Refusing to overwrite existing Redis data'}
# Record exact ownership before writing so interrupted runs have bounded cleanup instructions.
$state=@{fixtureKind='SYNTHETIC_REDIS_SEGMENTS_WITHOUT_EMBEDDINGS';createdAtUtc=[DateTime]::UtcNow.ToString('o');indexedDocumentId=$id;unindexedDocumentId=$old.documentId;fileId=$upload.data.fileId;ownerId=$login.data.user.id;otherUserId=$registered.data.id;segmentIds=$segmentIds;expectedContents=$parts;externalModelCalls=0;vectorWrites=0}
$state|ConvertTo-Json -Depth 4|Set-Content $statePath -Encoding utf8
function Write-RedisJson([string]$Key,[object]$Value,[string]$Field){
    $json=ConvertTo-Json -InputObject $Value -Compress -Depth 4
    if($Field){$json|& docker exec -i $redis sh -c 'export REDISCLI_AUTH=$REDIS_PASSWORD; exec redis-cli -n 1 -x HSET "$1" "$2"' sh $Key $Field|Out-Null}
    else{$json|& docker exec -i $redis sh -c 'export REDISCLI_AUTH=$REDIS_PASSWORD; exec redis-cli -n 1 -x SET "$1"' sh $Key|Out-Null}
    if($LASTEXITCODE -ne 0){throw 'Synthetic Redis JSON write failed'}
    Redis @('EXPIRE',$Key,'86400')|Out-Null
}
Write-RedisJson "doc:$id" ([string]$state.ownerId) 'userId'
Write-RedisJson "doc:$id" 'default' 'knowledgeBaseId'
Write-RedisJson "doc_segments:$id" $segmentIds
for($i=0;$i -lt $parts.Count;$i++){Write-RedisJson "segment:$($segmentIds[$i])" $parts[$i]}
Write-Output "Created isolated synthetic segment-read fixture $id. No indexing API, embedding call, or vector write was made."
