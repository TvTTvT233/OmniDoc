[CmdletBinding()]
param([string]$RunId='20261028-r2')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/review-m0-20261027'
$fixture=Get-Content (Join-Path $evidence "manual-$RunId/fixture.json") -Raw|ConvertFrom-Json
$file=$fixture.files[1]
$account=(Get-Content (Join-Path $repo '.local/baseline-runtime/test-session.json') -Raw|ConvertFrom-Json).account
$login=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/users/login -ContentType application/json -Body (@{username=$account.username;password=$account.password}|ConvertTo-Json)
$jwt=$login.data.accessToken
$encoded=$jwt.Split('.')[1].Replace('-','+').Replace('_','/');$encoded+='='*((4-$encoded.Length%4)%4)
$owner=([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))|ConvertFrom-Json).userId.ToString()
$secret=(Get-Content (Join-Path $evidence 'internal-service.private.env')|Where-Object {$_ -match '^OMNIDOC_REVIEW_INTERNAL_TOKEN='}) -replace '^OMNIDOC_REVIEW_INTERNAL_TOKEN=',''
$base='http://127.0.0.1:8082/internal/review-files/'+$file.fileId
$checks=[Collections.Generic.List[object]]::new()
function Check([string]$Name,[bool]$Pass,[int]$Status){
    $checks.Add(@{name=$Name;passed=$Pass;http=$Status})
    @($checks)|ConvertTo-Json -Depth 4|Set-Content (Join-Path $evidence 'internal-read-checks.json')
    if(-not $Pass){throw "CHECK_FAILED $Name"}
}
foreach($entry in @(
    @{name='missing_service_credential';headers=@{};status=401},
    @{name='wrong_service_credential';headers=@{'X-OmniDoc-Review-Token'='wrong-credential'};status=401},
    @{name='jwt_alone_not_service_credential';headers=@{Authorization='Bearer '+$jwt};status=401},
    @{name='service_credential_wrong_owner';headers=@{'X-OmniDoc-Review-Token'=$secret;'X-OmniDoc-Review-OwnerId'='another-owner'};status=404},
    @{name='service_credential_actual_owner';headers=@{'X-OmniDoc-Review-Token'=$secret;'X-OmniDoc-Review-OwnerId'=$owner};status=200}
)){
    $r=Invoke-WebRequest -Uri "$base/metadata" -Headers $entry.headers -SkipHttpErrorCheck
    Check $entry.name ([int]$r.StatusCode -eq $entry.status) ([int]$r.StatusCode)
}
$headers=@{'X-OmniDoc-Review-Token'=$secret;'X-OmniDoc-Review-OwnerId'=$owner}
$path=Join-Path $evidence 'internal-read-copy.pdf'
$r=Invoke-WebRequest -Uri "$base/content" -Headers $headers -OutFile $path -PassThru -SkipHttpErrorCheck
Check 'internal_content_matches_original_bytes' ($r.StatusCode -eq 200 -and (Get-FileHash $path).Hash -eq (Get-FileHash $file.localPath).Hash) $r.StatusCode
$r=Invoke-WebRequest -Method Post -Uri "$base/metadata" -Headers $headers -SkipHttpErrorCheck
Check 'internal_channel_read_only' ($r.StatusCode -eq 405) $r.StatusCode
$r=Invoke-WebRequest -Uri ('http://127.0.0.1:8080/internal/review-files/'+$file.fileId+'/metadata') -Headers @{Authorization='Bearer '+$jwt} -SkipHttpErrorCheck
Check 'internal_channel_not_gateway_route' ($r.StatusCode -eq 404) $r.StatusCode
Write-Output "Internal file access passed $($checks.Count) checks."
