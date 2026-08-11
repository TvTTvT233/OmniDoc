[CmdletBinding()]
param(
    [switch]$Integrated,
    [string]$RepositoryRoot,
    [string]$ReportDirectory
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..\..'
}
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if ([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $ReportDirectory = Join-Path $RepositoryRoot 'ai-service\target\memory-v1-acceptance'
}
$ReportDirectory = [System.IO.Path]::GetFullPath($ReportDirectory)
$reportPath = Join-Path $ReportDirectory 'memory-m03-stage-report.json'

$common = Join-Path $PSScriptRoot 'stage-gate-common.ps1'
$checks = Join-Path $PSScriptRoot 'm03-stage-gate-checks.ps1'
$fixtureCheck = Join-Path $PSScriptRoot 'test-m03-stage-gate-fixtures.ps1'
$sensitiveCheck = Join-Path $PSScriptRoot 'assert-no-sensitive-data.ps1'
. $common
. $checks

$scriptAssets = @(
    $common,
    $checks,
    $fixtureCheck,
    $sensitiveCheck,
    (Join-Path $PSScriptRoot 'run-m02-gate.ps1'),
    (Join-Path $PSScriptRoot 'run-m03-runtime-gate.ps1'),
    (Join-Path $PSScriptRoot 'run-m03-gate.ps1'),
    (Join-Path $PSScriptRoot 'run-frontend-contract-gate.ps1')
)
Assert-MemoryPowerShellSyntax -Paths $scriptAssets

$cmdWrapper = Join-Path $PSScriptRoot 'run-m03-gate.cmd'
if (-not (Test-Path -LiteralPath $cmdWrapper -PathType Leaf)) {
    throw "M03 complete stage gate wrapper is missing: $cmdWrapper"
}

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fixtureCheck
if ($LASTEXITCODE -ne 0) {
    throw "M03 stage-gate fixture self-test failed with exit code $LASTEXITCODE"
}

$productionTargets = @(
    'M03_RUNTIME_STATE_MODEL',
    'M03_RUNTIME_STATE_SERVICE',
    'M03_RUNTIME_STATE_REDIS',
    'M03_RUNTIME_STATE_WHITELIST',
    'M03_RUNTIME_STATE_BOUNDED_TTL',
    'M03_RUNTIME_CLEAR_CONVERSATION',
    'M03_CONVERSATION_DELETE_RUNTIME_CASCADE',
    'M03_TASK_SNAPSHOT_DELETE_CASCADE',
    'M03_APPROVAL_REFERENCE_DELETE_CASCADE',
    'M03_CONTINUATION_OWNER_BEFORE_STATE_USE',
    'M03_APPROVAL_CONFIRM_OWNER_BEFORE_MUTATION',
    'M03_APPROVAL_CANCEL_OWNER_BEFORE_MUTATION'
)
$requiredTargets = $productionTargets + @(
    'M03_RUNTIME_SPECIALIST_TESTS',
    'M03_FOUR_TARGET_TESTS_ACTIVE'
)
$regressionGroups = @(
    'MemoryV1ExpectedContractTest:four complete M03 targets',
    'RuntimeState and ContextManager compatibility tests',
    'Conversation deletion and Agent ownership specialist tests when present',
    'AgentExecutionServiceTest and AgentControllerTest'
)
$targetMethods = @(
    'm03RuntimeStateUsesWhitelistAndBoundedTtl',
    'm03RuntimeStateDoesNotLeakAcrossConversations',
    'm03ConversationDeletionCascadesRuntimeDerivatives',
    'm03ContinuationAndApprovalCancellationEnforceOwnershipInsideService'
)

$assetRun = Invoke-MemoryMavenSelection `
    -RepositoryRoot $RepositoryRoot `
    -Name 'Memory complete M03 gate asset' `
    -Selection 'MemoryV1ReportSchemaTest' `
    -ReportNamePattern 'TEST-com.javaee.aiservice.memory.acceptance.MemoryV1ReportSchemaTest.xml'
$assetCounts = $assetRun.Counts

if (-not $Integrated) {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M03' `
        -Status 'ASSET_VERIFIED' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing @('INTEGRATED_MODE_NOT_REQUESTED') `
        -TargetTestsExpected 4 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'Complete M03 production behavior and four frozen target tests were not executed.',
            'No real Redis, MySQL, model, browser, remote Embedding, or Memory Qdrant collection was used.',
            'A stage asset report is not Memory V1 AUTO_VERIFIED.'
        )
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) {
        throw "M03 stage report boundary check failed with exit code $LASTEXITCODE"
    }
    Write-Host '[ASSET_VERIFIED] Complete M03 stage-gate fixtures, scripts, and report contract passed self-check.'
    Write-Host '[PENDING_INTEGRATION] Complete M03 production behavior was not evaluated.'
    exit 0
}

$sourceBundle = Get-MemoryM03SourceBundle -RepositoryRoot $RepositoryRoot
$readiness = Test-MemoryM03ProductionReadiness `
    -RuntimeText $sourceBundle.Runtime `
    -ConversationText $sourceBundle.Conversation `
    -AgentText $sourceBundle.Agent
$missing = [System.Collections.Generic.List[string]]::new()
foreach ($reason in $readiness.Missing) {
    $missing.Add($reason)
}

$testRoot = Join-Path $RepositoryRoot 'ai-service\src\test\java'
$runtimeTests = @()
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    $runtimeTests = @(Get-ChildItem -LiteralPath $testRoot -Recurse -File -Filter '*RuntimeState*Test.java')
}
if ($runtimeTests.Count -eq 0) {
    $missing.Add('M03_RUNTIME_SPECIALIST_TESTS')
}

$expectedContractPath = Join-Path $testRoot 'com\javaee\aiservice\memory\MemoryV1ExpectedContractTest.java'
$expectedContractText = if (Test-Path -LiteralPath $expectedContractPath -PathType Leaf) {
    Get-Content -LiteralPath $expectedContractPath -Raw -Encoding utf8
} else {
    ''
}
foreach ($method in $targetMethods) {
    if ($expectedContractText -notmatch "(?m)\b$([regex]::Escape($method))\s*\(") {
        $missing.Add("M03_TARGET_INVENTORY_MISSING:$method")
    }
}

if ($missing.Count -gt 0) {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M03' `
        -Status 'PENDING_INTEGRATION' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing $missing.ToArray() `
        -TargetTestsExpected 4 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'Complete M03 Runtime, deletion cascade, or service-level owner-ordering production evidence is missing.',
            'All four frozen M03 target tests must execute with skipped equal to zero after production readiness is present.',
            'A pending M03 stage report is not Memory V1 AUTO_VERIFIED.'
        )
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) { throw "M03 pending report boundary check failed with exit code $LASTEXITCODE" }
    Write-Host "[PENDING_INTEGRATION] Missing complete M03 evidence: $($missing -join ', ')"
    exit 2
}

$selection = 'MemoryV1ExpectedContractTest,*RuntimeState*Test,*ContextManager*CompatibilityTest,*Conversation*Deletion*Test,*Deletion*Cascade*Test,*Agent*Ownership*Test,*Approval*Ownership*Test,AgentExecutionServiceTest,AgentControllerTest'
try {
    $integratedRun = Invoke-MemoryMavenSelection `
        -RepositoryRoot $RepositoryRoot `
        -Name 'Memory complete M03 integrated stage' `
        -Selection $selection `
        -ReportNamePattern 'TEST-com.javaee.aiservice.*.xml'
} catch {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M03' `
        -Status 'FAILED' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $true `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing @('M03_TEST_SELECTION_FAILED') `
        -TargetTestsExpected 4 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'The complete M03 Maven stage selection failed; see the local console output.',
            'No final Memory V1 acceptance conclusion was produced.'
        )
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) { throw "M03 failed report boundary check failed with exit code $LASTEXITCODE" }
    throw
}

$expectedReport = $integratedRun.Reports |
    Where-Object { $_.Name -eq 'TEST-com.javaee.aiservice.memory.MemoryV1ExpectedContractTest.xml' } |
    Select-Object -First 1
$targetSkipped = 0
$targetMissing = [System.Collections.Generic.List[string]]::new()
if ($null -eq $expectedReport) {
    foreach ($method in $targetMethods) {
        $targetMissing.Add("M03_TARGET_NOT_RUN:$method")
    }
} else {
    [xml]$expectedXml = Get-Content -LiteralPath $expectedReport.FullName -Raw -Encoding utf8
    $testCases = @($expectedXml.testsuite.testcase)
    foreach ($method in $targetMethods) {
        $testCase = $testCases | Where-Object { $_.name -eq $method } | Select-Object -First 1
        if ($null -eq $testCase) {
            $targetMissing.Add("M03_TARGET_NOT_RUN:$method")
            continue
        }
        if (@($testCase.ChildNodes | Where-Object { $_.LocalName -eq 'skipped' }).Count -gt 0) {
            $targetSkipped++
        }
    }
}

if ($targetMissing.Count -gt 0 -or $targetSkipped -gt 0) {
    $pendingReasons = [System.Collections.Generic.List[string]]::new()
    foreach ($reason in $targetMissing) { $pendingReasons.Add($reason) }
    if ($targetSkipped -gt 0) { $pendingReasons.Add('M03_TARGET_TESTS_SKIPPED') }
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M03' `
        -Status 'PENDING_INTEGRATION' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $integratedRun.Counts `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing $pendingReasons.ToArray() `
        -TargetTestsExpected 4 `
        -TargetTestsSkipped $targetSkipped `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'All four named M03 target tests must execute with skipped equal to zero.',
            'M03 stage verification cannot promote the final Memory V1 status.'
        )
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) { throw "M03 pending report boundary check failed with exit code $LASTEXITCODE" }
    Write-Host "[PENDING_INTEGRATION] M03 target tests missing=$($targetMissing.Count), skipped=$targetSkipped."
    exit 2
}

Write-MemoryStageReport `
    -RepositoryRoot $RepositoryRoot `
    -ReportPath $reportPath `
    -Stage 'M03' `
    -Status 'STAGE_VERIFIED' `
    -AssetCounts $assetCounts `
    -IntegratedCounts $integratedRun.Counts `
    -Ready $true `
    -RequiredTargets $requiredTargets `
    -ReadinessMissing @() `
    -TargetTestsExpected 4 `
    -TargetTestsSkipped 0 `
    -RegressionGroups $regressionGroups `
    -Unverified @(
        'This stage verifies complete M03 behavior in deterministic local tests only.',
        'No real Redis outage, real MySQL, model, browser, remote Embedding, complete M07 gate, or human acceptance was exercised.',
        'STAGE_VERIFIED is not Memory V1 AUTO_VERIFIED or human acceptance.'
    )
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
if ($LASTEXITCODE -ne 0) { throw "M03 stage report boundary check failed with exit code $LASTEXITCODE" }
Write-Host '[STAGE_VERIFIED] All four M03 targets ran with skipped=0 and Runtime/Agent regressions passed.'
Write-Host '[PENDING_INTEGRATION] M04-M07 and final Memory V1 acceptance remain pending.'
exit 0
