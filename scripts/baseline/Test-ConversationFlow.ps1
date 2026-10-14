[CmdletBinding()]
param([string]$BaseUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runtime=Join-Path $repo '.local/baseline-runtime'
$session=Get-Content (Join-Path $runtime 'test-session.json') -Raw | ConvertFrom-Json
$login=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/users/login" -ContentType application/json -Body (@{username=$session.account.username;password=$session.account.password}|ConvertTo-Json)
$headers=@{Authorization="Bearer $($login.data.accessToken)"}
$checks=[Collections.Generic.List[object]]::new()
function Check([string]$Name,[bool]$Passed){$checks.Add([pscustomobject]@{name=$Name;passed=$Passed});if(-not $Passed){throw "Check failed: $Name"}}
function Api([string]$Method,[string]$Path,[object]$Body=$null){
    $a=@{Method=$Method;Uri="$BaseUrl$Path";Headers=$headers;TimeoutSec=30}
    if($null -ne $Body){$a.ContentType='application/json';$a.Body=($Body|ConvertTo-Json -Compress)}
    Invoke-RestMethod @a
}
$errorType=$null
try{
    $conv=Api POST '/api/ai/conversations' @{title='Baseline storage-only conversation'}
    $id=$conv.conversationId
    Check 'create_conversation' ($id.Length -gt 0 -and $conv.status -eq 'ACTIVE')
    $list=Api GET '/api/ai/conversations'
    Check 'list_contains_created' (@($list.items|Where-Object conversationId -eq $id).Count -eq 1)
    $detail=Api GET "/api/ai/conversations/$id"
    Check 'reload_conversation' ($detail.conversationId -eq $id)
    $messages=Api GET "/api/ai/conversations/$id/messages"
    Check 'new_conversation_empty_messages' (@($messages.items).Count -eq 0)
    $archived=Api POST "/api/ai/conversations/$id/archive"
    Check 'archive_conversation' ($archived.status -eq 'ARCHIVED')
    $restored=Api POST "/api/ai/conversations/$id/restore"
    Check 'restore_conversation' ($restored.status -eq 'ACTIVE')
    $deleted=Api DELETE "/api/ai/conversations/$id"
    Check 'permanent_delete_conversation' ($deleted.deleted -eq $true)
    $gone=Invoke-WebRequest -Uri "$BaseUrl/api/ai/conversations/$id" -Headers $headers -SkipHttpErrorCheck
    Check 'deleted_conversation_404' ($gone.StatusCode -eq 404)
    $kept=Api POST '/api/ai/conversations' @{title='Baseline restart persistence'}
    @{conversationId=$kept.conversationId;title=$kept.title}|ConvertTo-Json|Set-Content (Join-Path $runtime 'conversation-fixture.json') -Encoding utf8
}catch{$errorType=$_.Exception.GetType().Name;Write-Warning "Conversation flow stopped: $errorType"}
$report=@{generatedAtUtc=[DateTime]::UtcNow.ToString('o');status=$(if($errorType){'FAILED'}else{'PASSED'});checks=@($checks.ToArray());errorType=$errorType;mode='NO_EXTERNAL_MODELS';modelCalls=0}
$report|ConvertTo-Json -Depth 6|Set-Content (Join-Path $runtime 'conversation-flow.json') -Encoding utf8
$report|ConvertTo-Json -Depth 6
if($errorType){exit 1}
