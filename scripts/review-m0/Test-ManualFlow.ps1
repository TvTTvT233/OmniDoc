[CmdletBinding()]
param([string]$BaseUrl='http://127.0.0.1:8080',[string]$RunId=(Get-Date -Format 'yyyyMMdd-HHmmss'),[switch]$ResumeAfterFirstJob)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/review-m0-20261027'
$fixtureDir=Join-Path $evidence "manual-$RunId"
$checks=[Collections.Generic.List[object]]::new()
$fixture=[ordered]@{kind='REVIEW_M0_MANUAL_RUNTIME';runId=$RunId;caseId=$null;files=@();reports=@();jobs=@()}
if($ResumeAfterFirstJob){
    $fixture=Get-Content (Join-Path $fixtureDir 'fixture.json') -Raw|ConvertFrom-Json -AsHashtable
    if($fixture.jobs.Count -ne 1 -or $fixture.reports.Count -ne 0){throw 'Resume requires the original first queued job'}
    Copy-Item (Join-Path $fixtureDir 'checks.json') (Join-Path $fixtureDir 'checks-before-resume.json') -ErrorAction Stop
    Copy-Item (Join-Path $fixtureDir 'result.json') (Join-Path $fixtureDir 'result-before-resume.json') -ErrorAction Stop
    foreach($check in (Get-Content (Join-Path $fixtureDir 'checks.json') -Raw|ConvertFrom-Json)){if($check.passed){$checks.Add($check)}}
}else{New-Item -ItemType Directory -Path $fixtureDir -ErrorAction Stop|Out-Null}
function Save-Progress {
    $fixture|ConvertTo-Json -Depth 12|Set-Content (Join-Path $fixtureDir 'fixture.json') -Encoding utf8
    @($checks)|ConvertTo-Json -Depth 8|Set-Content (Join-Path $fixtureDir 'checks.json') -Encoding utf8
}
function Check([string]$Name,[bool]$Pass,$Observed=$null){
    $checks.Add(@{name=$Name;passed=$Pass;observed=$Observed});Save-Progress
    if(-not $Pass){throw "CHECK_FAILED $Name"}
}
function Api([string]$Method,[string]$Path,$Body=$null,[string]$Token=$script:tokenA,[string]$Key=''){
    $headers=@{};if($Token){$headers.Authorization='Bearer '+$Token}
    if($Method -ne 'GET'){$headers['Idempotency-Key']=if($Key){$Key}else{[guid]::NewGuid().ToString('N')}}
    $args=@{Method=$Method;Uri=$BaseUrl+$Path;Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=30}
    if($null -ne $Body){$args.ContentType='application/json; charset=utf-8';$args.Body=$Body|ConvertTo-Json -Depth 15 -Compress}
    $response=Invoke-WebRequest @args
    $json=$null;if($response.Headers['Content-Type'] -match 'json'){$json=$response.Content|ConvertFrom-Json}
    return @{status=[int]$response.StatusCode;data=$json;content=$response.Content;headers=$response.Headers}
}
function Login([string]$Path){
    $private=Get-Content $Path -Raw|ConvertFrom-Json
    $response=Invoke-RestMethod -Method Post -Uri ($BaseUrl+'/api/users/login') -ContentType application/json -Body (@{username=$private.account.username;password=$private.account.password}|ConvertTo-Json)
    if(-not $response.data.accessToken){throw 'LOGIN_FAILED'}
    return $response.data.accessToken
}
function New-Pdf([string]$Name,[string]$Text){
    $path=Join-Path $fixtureDir $Name
    $content="BT /F1 12 Tf 72 720 Td ($Text) Tj ET"
    $objects=@('<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',("<< /Length $($content.Length) >>`nstream`n$content`nendstream"))
    $builder=[Text.StringBuilder]::new("%PDF-1.4`n");$offsets=[Collections.Generic.List[int]]::new()
    for($i=0;$i -lt $objects.Count;$i++){$offsets.Add($builder.Length);$null=$builder.Append("$($i+1) 0 obj`n$($objects[$i])`nendobj`n")}
    $xref=$builder.Length;$null=$builder.Append("xref`n0 6`n0000000000 65535 f `n")
    foreach($offset in $offsets){$null=$builder.Append($offset.ToString('0000000000')+" 00000 n `n")}
    $null=$builder.Append("trailer`n<< /Size 6 /Root 1 0 R >>`nstartxref`n$xref`n%%EOF`n")
    [IO.File]::WriteAllBytes($path,[Text.Encoding]::ASCII.GetBytes($builder.ToString()));return $path
}
function Upload([string]$Path){
    $handler=[Net.Http.HttpClientHandler]::new();$handler.UseProxy=$false;$client=[Net.Http.HttpClient]::new($handler)
    $form=[Net.Http.MultipartFormDataContent]::new();$part=[Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($Path))
    $part.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf');$form.Add($part,'file',[IO.Path]::GetFileName($Path))
    $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$script:tokenA)
    try{$response=$client.PostAsync($BaseUrl+'/api/files/owned',$form).GetAwaiter().GetResult();$body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult();Check 'owned_upload' ([int]$response.StatusCode -eq 201) @{http=[int]$response.StatusCode};$file=$body|ConvertFrom-Json;$fixture.files+=@{fileId=$file.fileId;originalName=$file.originalFileName;localPath=$Path};Save-Progress;return $file}
    finally{$form.Dispose();$client.Dispose();$handler.Dispose()}
}
function Await-Report($Job){
    if($Job.jobId -notin $fixture.jobs){$fixture.jobs+=$Job.jobId};Save-Progress
    $deadline=[datetime]::UtcNow.AddSeconds(60)
    do{$response=Api GET "/api/review-m0/cases/$($fixture.caseId)/jobs/$($Job.jobId)";$current=$response.data;if($current.status -in @('SUCCEEDED','FAILED','CANCELLED')){break};Start-Sleep -Milliseconds 250}while([datetime]::UtcNow -lt $deadline)
    Check 'real_worker_job_succeeded' ($current.status -eq 'SUCCEEDED') @{state=$current.status;errorCode=$current.error.errorCode}
    $report=(Api GET "/api/review-m0/cases/$($fixture.caseId)/reports/current").data
    $fixture.reports+=$report.reportId;Save-Progress
    $report|ConvertTo-Json -Depth 30|Set-Content (Join-Path $fixtureDir ($report.reportId+'.private.json')) -Encoding utf8
    return $report
}
try{
    $script:tokenA=Login (Join-Path $repo '.local/baseline-runtime/test-session.json')
    $tokenB=Login (Join-Path $repo '.local/baseline-evidence/segment-access-2026-10-16/other-session.private.json')
    if(-not $ResumeAfterFirstJob){
    Check 'unauthenticated_review_rejected' ((Api GET '/api/review-m0/cases' $null '').status -eq 401)
    $caseBody=@{title="Manual runtime $RunId";processingMode='LOCAL_ONLY'};$key='manual-case-'+$RunId
    $created=Api POST '/api/review-m0/cases' $caseBody $tokenA $key
    Check 'create_case' ($created.status -eq 201);$fixture.caseId=$created.data.caseId;Save-Progress
    $base="/api/review-m0/cases/$($fixture.caseId)"
    $replay=Api POST '/api/review-m0/cases' $caseBody $tokenA $key
    Check 'create_case_idempotent' ($replay.status -eq 201 -and $replay.data.caseId -eq $fixture.caseId)
    Check 'create_case_payload_conflict' ((Api POST '/api/review-m0/cases' @{title='Different';processingMode='LOCAL_ONLY'} $tokenA $key).status -eq 409)
    Check 'case_cross_owner_rejected' ((Api GET $base $null $tokenB).status -eq 404)
    $notice=Upload (New-Pdf 'official-notice.pdf' 'SYNTHETIC: application and proof required. Manual rules are user-confirmed.')
    $basis=Api POST "$base/official-bases" @{fileId=$notice.fileId;role='APPLICATION_NOTICE'}
    Check 'attach_official_basis' ($basis.status -eq 201)
    $requirements=@()
    foreach($entry in @(@{name='Application';file='application.pdf'},@{name='Proof';file='proof.pdf'})){
        $r=Api POST "$base/checklist-draft/requirements" @{displayName=$entry.name;enabled=$true;applicability='APPLIES';minimumCount=1;fileNameRule=@{kind='EXACT';pattern=$entry.file;caseSensitive=$true}}
        $item=@($r.data.items|Where-Object displayName -eq $entry.name)[0]
        Check 'manual_requirement_origin' ($r.status -eq 201 -and $item.origin -eq 'USER_ADDED');$requirements+=$item
    }
    $draft=(Api GET "$base/checklist-draft").data
    $blocked=Api POST "$base/checklist-snapshots" @{expectedChecklistRevision=$draft.revision;acknowledgedRequirementIds=@($requirements.requirementId)}
    Check 'unparsed_official_basis_blocks_publication' ($blocked.status -eq 409 -and $blocked.data.errorCode -eq 'REVIEW_M0_BASIS_CANNOT_CONFIRM')
    $detached=Api DELETE "$base/official-bases/$($basis.data.basisId)" @{expectedVersion=$basis.data.version}
    Check 'detach_basis_reference_preserves_original_file' ($detached.status -eq 200 -and (Api GET "/api/files/owned/$($notice.fileId)/metadata").status -eq 200)
    $reconfirmed=Api PATCH "$base/checklist-draft/requirements/$($requirements[0].requirementId)" @{expectedVersion=$requirements[0].itemVersion;displayName=$requirements[0].displayName;enabled=$true;applicability='APPLIES';minimumCount=1;fileNameRule=@{kind='EXACT';pattern='application.pdf';caseSensitive=$true}}
    Check 'reconfirm_manual_draft_after_basis_change' ($reconfirmed.status -eq 200)
    $draft=(Api GET "$base/checklist-draft").data
    Check 'partial_confirmation_rejected' ((Api POST "$base/checklist-snapshots" @{expectedChecklistRevision=$draft.revision;acknowledgedRequirementIds=@($requirements[0].requirementId)}).status -eq 409)
    $snapshot=Api POST "$base/checklist-snapshots" @{expectedChecklistRevision=$draft.revision;acknowledgedRequirementIds=@($requirements.requirementId)}
    Check 'publish_confirmed_checklist' ($snapshot.status -eq 201);$snapshotId=$snapshot.data.checklistSnapshotId
    $file=Upload (New-Pdf 'source-original.pdf' 'SYNTHETIC application material, preserved original.')
    $before=Api GET "/api/files/owned/$($file.fileId)/metadata"
    $attached=Api POST "$base/submission-sources" @{fileId=$file.fileId;submissionFileName='wrong-name.pdf'}
    Check 'attach_submission' ($attached.status -eq 201);$material=$attached.data.materials[0]
    $assignment=Api PUT "$base/assignments" @{expectedAssignmentRevision=0;assignments=@(@{materialId=$material.materialId;requirementId=$requirements[0].requirementId;status='USER_CONFIRMED'})}
    Check 'confirm_assignment' ($assignment.status -eq 200)
    $unconfirmed=Api POST "$base/reviews" @{mode='FULL';checklistSnapshotId=$snapshotId;uploadConfirmationId='not-confirmed';baseReportId=$null;changedMaterialIds=@()}
    Check 'review_without_upload_confirmation_rejected' ($unconfirmed.status -eq 409 -and $unconfirmed.data.errorCode -eq 'REVIEW_M0_UPLOAD_NOT_CONFIRMED')
    $state=(Api GET $base).data
    $upload=(Api POST "$base/upload-confirmations" @{checklistSnapshotId=$snapshotId;materialSetRevision=$state.materialSetRevision;acknowledgedUploadComplete=$true}).data
    $reviewBody=@{mode='FULL';checklistSnapshotId=$snapshotId;uploadConfirmationId=$upload.uploadConfirmationId;baseReportId=$null;changedMaterialIds=@()}
    $submitted=Api POST "$base/reviews" $reviewBody
    Check 'formal_review_accepted' ($submitted.status -eq 202)
    }else{
        $base="/api/review-m0/cases/$($fixture.caseId)"
        $state=(Api GET $base).data
        $snapshotId=$state.currentChecklistSnapshotId
        $snapshot=(Api GET "$base/checklist-snapshots/$snapshotId").data
        $requirements=@($snapshot.items|Sort-Object @{Expression={if($_.displayName -eq 'Application'){0}else{1}}})
        $file=@{fileId=$fixture.files[1].fileId}
        $before=Api GET "/api/files/owned/$($file.fileId)/metadata"
        $material=@((Api GET "$base/materials").data.items)[0]
        $upload=(Api POST "$base/upload-confirmations" @{checklistSnapshotId=$snapshotId;materialSetRevision=$state.materialSetRevision;acknowledgedUploadComplete=$true}).data
        $reviewBody=@{mode='FULL';checklistSnapshotId=$snapshotId;uploadConfirmationId=$upload.uploadConfirmationId;baseReportId=$null;changedMaterialIds=@()}
        $submitted=@{data=@{jobId=$fixture.jobs[0]}}
        Check 'resumed_original_queued_job_without_recreating_case' ($fixture.jobs.Count -eq 1)
    }
    $first=Await-Report $submitted.data
    Check 'missing_and_name_findings' ($first.summary.missingCount -eq 1 -and $first.summary.fileNameNoncompliantCount -eq 1 -and $first.assessment -eq 'M0_NOT_READY') $first.summary
    Check 'all_deterministic_findings_have_both_evidence_sides' (@($first.findings|Where-Object {$_.ruleEvidence.Count -eq 0 -or $_.materialEvidence.Count -eq 0}).Count -eq 0)
    $ignored=Api POST "$base/reports/$($first.reportId)/findings/$($first.findings[0].findingId)/decisions" @{decision='IGNORE';reason='Synthetic acceptance decision'}
    Check 'ignore_does_not_make_ready' ($ignored.status -eq 201 -and $ignored.data.effectiveAssessment -eq 'M0_NOT_READY')
    Check 'report_cross_owner_rejected' ((Api GET "$base/reports/$($first.reportId)" $null $tokenB).status -eq 404)
    Check 'download_cross_owner_rejected' ((Api GET "$base/materials/$($material.materialId)/download" $null $tokenB).status -eq 404)
    $renamed=Api PATCH "$base/materials/$($material.materialId)" @{expectedVersion=$material.version;submissionFileName='application.pdf'}
    Check 'rename_logical_name' ($renamed.status -eq 200)
    Check 'rename_marks_report_outdated' ((Api GET "$base/reports/$($first.reportId)").data.freshness -eq 'OUTDATED')
    $incremental=Await-Report (Api POST "$base/reviews" @{mode='INCREMENTAL';checklistSnapshotId=$snapshotId;uploadConfirmationId=$upload.uploadConfirmationId;baseReportId=$first.reportId;changedMaterialIds=@($material.materialId)}).data
    Check 'incremental_removes_only_name_issue' ($incremental.summary.missingCount -eq 1 -and $incremental.summary.fileNameNoncompliantCount -eq 0)
    $proof=Upload (New-Pdf 'proof-original.pdf' 'SYNTHETIC proof material, preserved original.')
    $proofAttach=Api POST "$base/submission-sources" @{fileId=$proof.fileId;submissionFileName='proof.pdf'}
    $proofMaterial=$proofAttach.data.materials[0]
    $currentAssignments=(Api GET "$base/assignments").data
    $null=Api PUT "$base/assignments" @{expectedAssignmentRevision=$currentAssignments.assignmentRevision;assignments=@(@{materialId=$material.materialId;requirementId=$requirements[0].requirementId;status='USER_CONFIRMED'},@{materialId=$proofMaterial.materialId;requirementId=$requirements[1].requirementId;status='USER_CONFIRMED'})}
    Check 'old_upload_confirmation_rejected' ((Api POST "$base/reviews" $reviewBody).status -eq 409)
    $state=(Api GET $base).data
    $upload2=(Api POST "$base/upload-confirmations" @{checklistSnapshotId=$snapshotId;materialSetRevision=$state.materialSetRevision;acknowledgedUploadComplete=$true}).data
    $final=Await-Report (Api POST "$base/reviews" @{mode='FULL';checklistSnapshotId=$snapshotId;uploadConfirmationId=$upload2.uploadConfirmationId;baseReportId=$null;changedMaterialIds=@()}).data
    Check 'supplement_and_rereview_ready' ($final.assessment -eq 'M0_READY' -and $final.freshness -eq 'CURRENT' -and $final.findings.Count -eq 0)
    $downloadPath=Join-Path $fixtureDir 'downloaded-copy.pdf'
    Invoke-WebRequest -Uri ($BaseUrl+"$base/materials/$($material.materialId)/download") -Headers @{Authorization='Bearer '+$tokenA} -OutFile $downloadPath
    Check 'download_original_bytes_unchanged' ((Get-FileHash $downloadPath).Hash -eq (Get-FileHash $fixture.files[1].localPath).Hash)
    $after=Api GET "/api/files/owned/$($file.fileId)/metadata"
    Check 'original_metadata_unchanged' (($before.data|ConvertTo-Json -Depth 8 -Compress) -ceq ($after.data|ConvertTo-Json -Depth 8 -Compress))
    $fixture.finalReportId=$final.reportId;Save-Progress
    @{status='PASSED';checks=@($checks);fixture=$fixture;externalModelCalls=0;syntheticModels=$false;completedAtUtc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json -Depth 15|Set-Content (Join-Path $fixtureDir 'result.json') -Encoding utf8
    Write-Output "Manual flow passed $($checks.Count) checks."
}catch{
    Save-Progress
    @{status='FAILED';checks=@($checks);errorType=$_.Exception.GetType().Name;lastPassed=@($checks|Where-Object passed).Count}|ConvertTo-Json -Depth 10|Set-Content (Join-Path $fixtureDir 'result.json') -Encoding utf8
    throw
}
