[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Initialize','Disabled','QueueSynthetic','Ready','Permissions','Historical','Deleted')][string]$Phase,
    [int]$TimeoutSeconds=120
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/consistency-evidence'
$statePath=Join-Path $evidence 'runtime-fixtures.json'
$ownerPath=Join-Path $repo '.local/baseline-runtime/test-session.json'
$otherPath=Join-Path $repo '.local/baseline-evidence/segment-access-2026-10-16/other-session.private.json'
$checks=[Collections.Generic.List[object]]::new()
function Request([string]$Method,[string]$Path,[object]$Body,[string]$Token,[string]$Base='http://127.0.0.1:8080') {
    $params=@{Method=$Method;Uri=$Base+$Path;SkipHttpErrorCheck=$true;TimeoutSec=40}
    if($Token){$params.Headers=@{Authorization='Bearer '+$Token}}
    if($null -ne $Body){$params.ContentType='application/json; charset=utf-8';$params.Body=($Body|ConvertTo-Json -Depth 15 -Compress)}
    $r=Invoke-WebRequest @params
    $bodyObject=$null;try{$bodyObject=$r.Content|ConvertFrom-Json}catch{}
    return [pscustomobject]@{http=[int]$r.StatusCode;body=$bodyObject}
}
function Login([string]$Path){
    $private=Get-Content -LiteralPath $Path -Raw|ConvertFrom-Json
    $r=Request 'POST' '/api/users/login' @{username=$private.account.username;password=$private.account.password} $null
    if($r.http -ne 200 -or $r.body.code -ne 200 -or -not $r.body.data.accessToken){throw 'Private synthetic account login failed'}
    return $r.body.data.accessToken
}
function Check([string]$Name,[bool]$Passed,[object]$Observed){
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed;observed=$Observed})
    if(-not $Passed){throw "Runtime assertion failed: $Name"}
}
function Wait-State([string]$Id,[string]$Token,[string[]]$States){
    $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $r=Request 'GET' "/api/documents/$Id/index-state" $null $Token
        if($r.http -ne 200){throw "Index state unavailable HTTP $($r.http)"}
        if($r.body.data.indexState -in $States){return $r.body.data}
        Start-Sleep -Milliseconds 500
    }while([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $($States -join ','); last state=$($r.body.data.indexState)"
}
$status='FAILED';$failure=$null
try {
    New-Item -ItemType Directory -Path $evidence -Force|Out-Null
    $owner=Login $ownerPath
    if($Phase -eq 'Initialize'){
        if(Test-Path -LiteralPath $statePath){throw 'Fixture manifest exists; refusing duplicate document creation'}
        $text="证据版本一 😀 第一段。`nSynthetic audit evidence alpha original.`n第二段只属于版本一。"
        $next="证据版本二 😀 已更新的段落。`nSynthetic audit evidence beta updated."
        $sample=Join-Path $evidence 'synthetic-consistency-source.txt'
        [IO.File]::WriteAllText($sample,$text,[Text.UTF8Encoding]::new($false))
        $upload=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/files/upload -Headers @{Authorization='Bearer '+$owner} -Form @{file=Get-Item -LiteralPath $sample}
        Check 'synthetic_original_upload' ($upload.code -eq 200 -and [bool]$upload.data.fileId) @{code=$upload.code}
        $created=Request 'POST' '/api/documents' @{title='Consistency runtime synthetic evidence';fileId=$upload.data.fileId;category='baseline'} $owner
        Check 'parse_creates_verified_revision_one' ($created.http -eq 200 -and $created.body.data.version -eq 1 -and $created.body.data.snapshotState -eq 'VERIFIED' -and $created.body.data.parseState -eq 'READY' -and $created.body.data.content -eq $text) @{http=$created.http;version=$created.body.data.version;snapshotState=$created.body.data.snapshotState;parseState=$created.body.data.parseState}
        $f=@{fixtureKind='CONSISTENCY_REAL_WORKER_SYNTHETIC_MODELS';createdAtUtc=[DateTime]::UtcNow.ToString('o');ownerSessionPath=$ownerPath;otherSessionPath=$otherPath;documentId=$created.body.data.id;fileId=$upload.data.fileId;newV1Version=1;expectedV1Text=$text;expectedV2Text=$next;redisDatabase=11;embeddingModel='synthetic-embedding-v1';embeddingDimension=16;externalModelCalls=0}
        $f|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $statePath -Encoding utf8
    }else{
        $f=Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json -AsHashtable
        if($f.fixtureKind -ne 'CONSISTENCY_REAL_WORKER_SYNTHETIC_MODELS'){throw 'Unexpected fixture manifest'}
        $id=$f.documentId
        switch($Phase){
            QueueSynthetic {
                $current=Request 'GET' "/api/documents/$Id/index-state" $null $owner
                $r=Request 'POST' "/api/documents/$Id/index" @{expectedVersion=$current.body.data.currentVersion;strategy='FIXED_LENGTH'} $owner
                Check 'public_request_requeues_with_explicit_synthetic_configuration' ($r.http -eq 202) @{http=$r.http;currentVersion=$current.body.data.currentVersion}
            }
            Disabled {
                $s=Wait-State $id $owner @('FAILED')
                Check 'production_worker_reports_disabled_embedding' ($s.errorCode -eq 'EMBEDDING_DISABLED' -and -not $s.queryable -and -not $s.activeGenerationId) @{indexState=$s.indexState;errorCode=$s.errorCode;queryable=$s.queryable}
            }
            Ready {
                $s=Wait-State $id $owner @('READY','FAILED')
                Check 'real_worker_publishes_current_revision' ($s.indexState -eq 'READY' -and $s.queryable -and $s.currentVersion -eq $s.indexedVersion -and [bool]$s.activeGenerationId) @{indexState=$s.indexState;currentVersion=$s.currentVersion;indexedVersion=$s.indexedVersion;queryable=$s.queryable;errorCode=$s.errorCode}
                $segments=Request 'GET' "/api/ai/rag/document/$id/segments" $null $owner
                Check 'published_generation_readable_via_real_ai_http' ($segments.http -eq 200 -and $segments.body.data.indexState -eq 'SEGMENTS_AVAILABLE' -and $segments.body.data.documentVersion -eq $s.currentVersion -and $segments.body.data.generationId -eq $s.activeGenerationId -and @($segments.body.data.segments).Count -gt 0) @{http=$segments.http;indexState=$segments.body.data.indexState;count=@($segments.body.data.segments).Count}
                if(-not $f.referenceId){
                    $ref=$segments.body.data.segments[0].reference
                    if(-not $ref.referenceId){throw 'Published segment is missing registered source reference'}
                    $f.referenceId=$ref.referenceId;$f.reference=$ref;$f.newV1Version=$s.currentVersion;$f.v1GenerationId=$s.activeGenerationId
                    $f|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $statePath -Encoding utf8
                }
                $refResponse=Request 'GET' "/api/documents/$id/references/$($f.referenceId)" $null $owner
                $start=[int]$f.reference.startOffset;$end=[int]$f.reference.endOffset
                Check 'registered_reference_matches_exact_original_slice' ($refResponse.http -eq 200 -and $refResponse.body.data.content -eq $f.expectedV1Text.Substring($start,$end-$start)) @{http=$refResponse.http;documentVersion=$refResponse.body.data.documentVersion;status=$refResponse.body.data.status;startOffset=$start;endOffset=$end}
            }
            Permissions {
                $other=Login $otherPath
                $unauth=Request 'GET' "/api/documents/$id/index-state" $null $null
                Check 'unauthenticated_document_state_401' ($unauth.http -eq 401) @{http=$unauth.http}
                $denied=Request 'GET' "/api/documents/$id/references/$($f.referenceId)" $null $other
                Check 'other_user_cannot_read_reference' ($denied.http -eq 404 -and -not $denied.body.data.content) @{http=$denied.http}
                $spoof=Invoke-WebRequest -Uri "http://127.0.0.1:8084/api/documents/$id" -Headers @{'X-User-Id'='1'} -SkipHttpErrorCheck
                Check 'direct_document_forged_header_401' ([int]$spoof.StatusCode -eq 401) @{http=[int]$spoof.StatusCode}
                $internal=Request 'POST' '/internal/index/tasks/claim' @{workerId='unauthorized-probe'} $owner 'http://127.0.0.1:8084'
                Check 'user_jwt_cannot_claim_internal_tasks' ($internal.http -eq 401) @{http=$internal.http}
                $missing=Request 'GET' "/api/documents/$id/references/no-such-reference" $null $owner
                Check 'unregistered_reference_rejected' ($missing.http -eq 404) @{http=$missing.http}
            }
            Historical {
                $r=Request 'GET' "/api/documents/$id/references/$($f.referenceId)" $null $owner
                $start=[int]$f.reference.startOffset;$end=[int]$f.reference.endOffset
                Check 'old_reference_remains_exact_historical_slice_after_save' ($r.http -eq 200 -and $r.body.data.status -eq 'HISTORICAL' -and $r.body.data.documentVersion -eq $f.newV1Version -and $r.body.data.content -eq $f.expectedV1Text.Substring($start,$end-$start)) @{http=$r.http;status=$r.body.data.status;documentVersion=$r.body.data.documentVersion;currentVersion=$r.body.data.currentVersion}
            }
            Deleted {
                $r=Request 'DELETE' "/api/documents/$id" $null $owner
                Check 'synthetic_document_soft_delete' ($r.http -eq 200) @{http=$r.http}
                $ref=Request 'GET' "/api/documents/$id/references/$($f.referenceId)" $null $owner
                Check 'deleted_document_historical_reference_unreadable' ($ref.http -eq 404 -and -not $ref.body.data.content) @{http=$ref.http}
                $state=Request 'GET' "/api/documents/$id/index-state" $null $owner
                Check 'deleted_document_unavailable_to_new_index_requests' ($state.http -eq 404) @{http=$state.http}
            }
        }
    }
    $status='PASSED'
}catch{$failure=$_.Exception.Message;throw}finally{
    @{phase=$Phase;status=$status;generatedAtUtc=[DateTime]::UtcNow.ToString('o');checks=@($checks);error=$failure;externalModelCalls=0;successModelBoundary='Test-only deterministic Embedding/answer; production worker and real HTTP/database/vectorstore'}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $evidence ("runtime-$Phase-"+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.json')) -Encoding utf8
}
Write-Output "$Phase passed $($checks.Count)/$($checks.Count). No external model calls."
