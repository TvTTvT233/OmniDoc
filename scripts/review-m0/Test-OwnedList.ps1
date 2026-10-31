[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/review-m0-20261027'
function Login([string]$Path){
    $account=(Get-Content $Path -Raw|ConvertFrom-Json).account
    (Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/users/login -ContentType application/json -Body (@{username=$account.username;password=$account.password}|ConvertTo-Json)).data.accessToken
}
$tokenA=Login (Join-Path $repo '.local/baseline-runtime/test-session.json')
$tokenB=Login (Join-Path $repo '.local/baseline-evidence/segment-access-2026-10-16/other-session.private.json')
$checks=[Collections.Generic.List[object]]::new()
function Check([string]$Name,[bool]$Passed){
    $checks.Add(@{name=$Name;passed=$Passed})
    @($checks)|ConvertTo-Json|Set-Content (Join-Path $evidence 'owned-list-checks.json')
    if(-not $Passed){throw "CHECK_FAILED $Name"}
}
function Request([string]$Query,[string]$Token){
    $headers=@{};if($Token){$headers.Authorization='Bearer '+$Token}
    $r=Invoke-WebRequest -Uri ('http://127.0.0.1:8080/api/files/owned'+$Query) -Headers $headers -SkipHttpErrorCheck
    @{status=[int]$r.StatusCode;data=($r.Content|ConvertFrom-Json)}
}
Check 'owned_list_requires_login' ((Request '' '').status -eq 401)
$all=Request '?page=1&size=100' $tokenA
Check 'owned_list_success' ($all.status -eq 200)
$ids=@($all.data.items.fileId)
$manual=Get-Content (Join-Path $evidence 'manual-20261028-r2/fixture.json') -Raw|ConvertFrom-Json
Check 'owned_list_includes_own_runtime_uploads' (@($manual.files|Where-Object {$_.fileId -notin $ids}).Count -eq 0)
$legacy=Get-Content (Join-Path $evidence 'original-file-ids.private.json') -Raw|ConvertFrom-Json
$failedUi=Get-Content (Join-Path $evidence 'ui-legacy-upload-residue.json') -Raw|ConvertFrom-Json
Check 'owned_list_excludes_legacy_unknown_owner' (@($ids|Where-Object {$_ -in $legacy -or $_ -eq $failedUi.fileId}).Count -eq 0)
$other=Request '?page=1&size=100' $tokenB
Check 'owned_list_cross_owner_isolation' ($other.status -eq 200 -and @($other.data.items|Where-Object {$_.fileId -in $ids}).Count -eq 0)
$paged=@();$page=1
do{
    $r=Request "?page=$page&size=1" $tokenA
    Check "owned_page_${page}_bounded" ($r.status -eq 200 -and $r.data.items.Count -le 1)
    $paged+=@($r.data.items.fileId);$page++
    if($page -gt 100){throw 'Unexpected page count'}
}while($r.data.hasMore)
Check 'pagination_stable_no_duplicates_or_missing' (($paged -join '|') -ceq ($ids -join '|') -and @($paged|Select-Object -Unique).Count -eq $paged.Count)
foreach($query in @('?page=0','?page=10001','?size=0','?size=101')){Check ('invalid_pagination_'+$query) ((Request $query $tokenA).status -eq 400)}
$json=$all.data|ConvertTo-Json -Depth 8 -Compress
Check 'list_contains_no_storage_locator_or_owner_identifier' ($json -notmatch '"(objectKey|bucketName|ownerId|filePath)"')
$report=Invoke-RestMethod -Uri ('http://127.0.0.1:8080/api/review-m0/cases/'+$manual.caseId+'/reports/current') -Headers @{Authorization='Bearer '+$tokenA}
Check 'prior_manual_ready_report_survives_final_service_restart' ($report.reportId -eq $manual.finalReportId -and $report.assessment -eq 'M0_READY' -and $report.freshness -eq 'CURRENT')
$copy=Join-Path $evidence 'final-owned-copy.pdf'
$r=Invoke-WebRequest -Uri ('http://127.0.0.1:8080/api/files/owned/'+$manual.files[1].fileId+'/content') -Headers @{Authorization='Bearer '+$tokenA} -OutFile $copy -PassThru
Check 'final_service_download_matches_preserved_upload' ($r.StatusCode -eq 200 -and (Get-FileHash $copy).Hash -eq (Get-FileHash $manual.files[1].localPath).Hash)
Write-Output "Owned list passed $($checks.Count) checks."
