[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runtime = Join-Path $repo '.local/baseline-runtime'
$runId = [guid]::NewGuid().ToString('N').Substring(0,12)
$checks = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [bool]$Passed) {
    $checks.Add([pscustomobject]@{ name=$Name; passed=$Passed })
    if (-not $Passed) { throw "Check failed: $Name" }
}
function Api([string]$Method,[string]$Path,[object]$Body,$Headers=@{}) {
    $args = @{ Method=$Method; Uri="$BaseUrl$Path"; Headers=$Headers; TimeoutSec=30 }
    if ($null -ne $Body) { $args.ContentType='application/json'; $args.Body=($Body | ConvertTo-Json -Depth 8 -Compress) }
    return Invoke-RestMethod @args
}
$errorType = $null
try {
    $account = @{ username="baseline_$runId"; password=[guid]::NewGuid().ToString('N'); email="baseline-$runId@example.invalid"; phone=('139' + (Get-Random -Minimum 10000000 -Maximum 99999999)) }
    $registered=Api POST '/api/users/register' $account
    Check 'register' ($registered.code -eq 200 -and $registered.data.id -gt 0)
    $login=Api POST '/api/users/login' @{username=$account.username;password=$account.password}
    Check 'login_jwt' ($login.code -eq 200 -and $login.data.accessToken.Length -gt 20)
    # Private local session is ignored by Git; response tokens are never included in the report.
    @{account=$account;token=$login.data.accessToken;userId=$login.data.user.id} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runtime 'test-session.json') -Encoding utf8
    $headers=@{Authorization="Bearer $($login.data.accessToken)"}
    $text="OmniDoc baseline synthetic document $runId. Initial text."
    $sample=Join-Path $runtime "sample-$runId.txt"
    [IO.File]::WriteAllText($sample,$text,[Text.UTF8Encoding]::new($false))
    $upload=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/files/upload" -Headers $headers -Form @{file=Get-Item $sample} -TimeoutSec 30
    Check 'file_upload' ($upload.code -eq 200 -and $upload.data.fileId.Length -gt 0)
    $created=Api POST '/api/documents' @{title="Baseline $runId";fileId=$upload.data.fileId;category='baseline'} $headers
    Check 'document_create_and_parse_exact_text' ($created.code -eq 200 -and $created.data.content.Trim() -eq $text)
    $documentId=$created.data.id
    $read=Api GET "/api/documents/$documentId" $null $headers
    Check 'document_read_exact_text' ($read.code -eq 200 -and $read.data.content.Trim() -eq $text)
    $updatedText="OmniDoc baseline synthetic document $runId. Saved revision two."
    $updated=Api PUT "/api/documents/$documentId" @{title="Baseline $runId";content=$updatedText;changeLog='Baseline save verification'} $headers
    Check 'document_save' ($updated.code -eq 200)
    $readAgain=Api GET "/api/documents/$documentId" $null $headers
    Check 'saved_content_reloaded' ($readAgain.code -eq 200 -and $readAgain.data.content -eq $updatedText)
    $versions=Api GET "/api/documents/$documentId/versions" $null $headers
    Check 'prior_version_present' ($versions.code -eq 200 -and @($versions.data | Where-Object {$_.content.Trim() -eq $text}).Count -ge 1)
    @{runId=$runId;documentId=$documentId;fileId=$upload.data.fileId;expectedContent=$updatedText} | ConvertTo-Json | Set-Content (Join-Path $runtime 'document-fixture.json') -Encoding utf8
} catch {
    $errorType=$_.Exception.GetType().Name
    Write-Warning "Document flow stopped: $errorType; see boolean checks in local report."
} finally {
    $report=[ordered]@{generatedAtUtc=[DateTime]::UtcNow.ToString('o');runId=$runId;baseUrl=$BaseUrl;status=$(if($errorType){'FAILED'}else{'PASSED'});checks=@($checks.ToArray());errorType=$errorType;externalModelCalls=0;syntheticInput=$true}
    $report | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runtime "document-flow-$runId.json") -Encoding utf8
    $report | ConvertTo-Json -Depth 6
}
if($errorType){exit 1}
