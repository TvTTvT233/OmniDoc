[CmdletBinding()]
param(
    [switch]$Integrated,
    [string]$RepositoryRoot,
    [string]$ReportDirectory,
    [string]$ConversationApiPath = 'web\src\api\conversation.js',
    [string]$ConversationStatePath,
    [string]$FrontendTestScript = 'test:memory-contract'
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
$reportPath = Join-Path $ReportDirectory 'memory-m06a-frontend-stage-report.json'

$common = Join-Path $PSScriptRoot 'stage-gate-common.ps1'
$sensitiveCheck = Join-Path $PSScriptRoot 'assert-no-sensitive-data.ps1'
. $common

$scriptAssets = @(
    $common,
    $sensitiveCheck,
    (Join-Path $PSScriptRoot 'run-m02-gate.ps1'),
    (Join-Path $PSScriptRoot 'run-frontend-contract-gate.ps1')
)
Assert-MemoryPowerShellSyntax -Paths $scriptAssets

$cmdWrapper = Join-Path $PSScriptRoot 'run-frontend-contract-gate.cmd'
if (-not (Test-Path -LiteralPath $cmdWrapper -PathType Leaf)) {
    throw "Frontend contract gate wrapper is missing: $cmdWrapper"
}

$requiredTargets = @(
    'M06A_CONVERSATION_API_MODULE',
    'M06A_CONVERSATION_STATE_MODULE',
    'M06A_FRONTEND_CONTRACT_TEST_SCRIPT',
    'M06A_FRONTEND_TARGETS_ACTIVE',
    'AGENT_CONTROLLER_REGRESSION'
)
$regressionGroups = @(
    'npm run test:memory-contract',
    'AgentExecutionServiceTest',
    'AgentControllerTest'
)
$assetRun = Invoke-MemoryMavenSelection `
    -RepositoryRoot $RepositoryRoot `
    -Name 'Memory M06-A frontend gate asset' `
    -Selection 'MemoryV1ReportSchemaTest' `
    -ReportNamePattern 'TEST-com.javaee.aiservice.memory.acceptance.MemoryV1ReportSchemaTest.xml'
$assetCounts = $assetRun.Counts

if (-not $Integrated) {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M06_A' `
        -Status 'ASSET_VERIFIED' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing @('INTEGRATED_MODE_NOT_REQUESTED') `
        -TargetTestsExpected 1 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'M06-A frontend Conversation API/state contract tests were not executed.',
            'No backend structured Conversation API, browser, real model, MySQL, Redis, or external service was evaluated.',
            'A stage asset report is not Memory V1 AUTO_VERIFIED.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) {
        throw "M06-A stage report boundary check failed with exit code $LASTEXITCODE"
    }
    Write-Host '[ASSET_VERIFIED] M06-A frontend stage-gate scripts and report contract passed self-check.'
    Write-Host '[PENDING_INTEGRATION] M06-A frontend behavior was not evaluated.'
    exit 0
}

$missing = [System.Collections.Generic.List[string]]::new()
$apiAbsolute = Join-Path $RepositoryRoot $ConversationApiPath
if (-not (Test-Path -LiteralPath $apiAbsolute -PathType Leaf)) {
    $missing.Add('M06A_CONVERSATION_API_MODULE')
} elseif ((Get-Content -LiteralPath $apiAbsolute -Raw -Encoding utf8) -notmatch '/api/ai/conversations') {
    $missing.Add('M06A_CONVERSATION_API_RESOURCE_PATH')
}

$stateAbsolute = $null
if (-not [string]::IsNullOrWhiteSpace($ConversationStatePath)) {
    $stateAbsolute = Join-Path $RepositoryRoot $ConversationStatePath
} else {
    $stateRoots = @(
        (Join-Path $RepositoryRoot 'web\src\utils'),
        (Join-Path $RepositoryRoot 'web\src\composables'),
        (Join-Path $RepositoryRoot 'web\src\stores')
    )
    foreach ($root in $stateRoots) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        $candidate = Get-ChildItem -LiteralPath $root -Recurse -File -Include '*conversation*.js', '*Conversation*.js' |
            Where-Object {
                (Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8) -match 'conversationId'
            } |
            Select-Object -First 1
        if ($null -ne $candidate) {
            $stateAbsolute = $candidate.FullName
            break
        }
    }
}
if ($null -eq $stateAbsolute -or -not (Test-Path -LiteralPath $stateAbsolute -PathType Leaf)) {
    $missing.Add('M06A_CONVERSATION_STATE_MODULE')
}

$packagePath = Join-Path $RepositoryRoot 'web\package.json'
$package = Get-Content -LiteralPath $packagePath -Raw -Encoding utf8 | ConvertFrom-Json
if ($package.scripts.PSObject.Properties.Name -notcontains $FrontendTestScript) {
    $missing.Add('M06A_FRONTEND_CONTRACT_TEST_SCRIPT')
}

if ($missing.Count -gt 0) {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M06_A' `
        -Status 'PENDING_INTEGRATION' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing $missing.ToArray() `
        -TargetTestsExpected 1 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'Required M06-A frontend API/state assets or the frontend contract script are missing.',
            'No M06-A frontend test or Agent/Controller regression was counted as passed.',
            'M06-B, M06-C, the backend structured API, and final M07 remain outside this stage gate.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host "[PENDING_INTEGRATION] Missing M06-A evidence: $($missing -join ', ')"
    exit 2
}

$webReportPath = Join-Path $ReportDirectory 'memory-m06a-vitest-report.json'
try {
    Push-Location (Join-Path $RepositoryRoot 'web')
    try {
        & npm.cmd run $FrontendTestScript -- '--reporter=json' "--outputFile=$webReportPath" | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend contract test failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $webReportPath -PathType Leaf)) {
        throw 'Frontend contract test did not produce the requested JSON report'
    }
    $webResult = Get-Content -LiteralPath $webReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $webCounts = [pscustomobject][ordered]@{
        run = [int]$webResult.numTotalTests
        failures = [int]$webResult.numFailedTests
        errors = 0
        skipped = [int]$webResult.numPendingTests
        reports = [int]$webResult.numTotalTestSuites
    }
    if ($webCounts.run -lt 1) {
        throw 'Frontend contract selection discovered zero tests'
    }

    $javaRun = Invoke-MemoryMavenSelection `
        -RepositoryRoot $RepositoryRoot `
        -Name 'Memory M06-A Agent/Controller regression' `
        -Selection 'AgentExecutionServiceTest,AgentControllerTest' `
        -ReportNamePattern 'TEST-com.javaee.aiservice.*.xml'
    $combinedCounts = Merge-MemoryTestCounts -Components @(
        [pscustomobject]@{ Name = 'frontendContract'; Counts = $webCounts },
        [pscustomobject]@{ Name = 'agentController'; Counts = $javaRun.Counts }
    )
} catch {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M06_A' `
        -Status 'FAILED' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $true `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing @('M06A_TEST_SELECTION_FAILED') `
        -TargetTestsExpected 1 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'The M06-A frontend or Agent/Controller test selection failed; see the local console output.',
            'No final Memory V1 acceptance conclusion was produced.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    throw
}

if ($webCounts.skipped -gt 0) {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M06_A' `
        -Status 'PENDING_INTEGRATION' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $combinedCounts `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing @('M06A_FRONTEND_TARGETS_SKIPPED') `
        -TargetTestsExpected 1 `
        -TargetTestsSkipped $webCounts.skipped `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'M06-A frontend target tests must execute with skipped equal to zero.',
            'M06-B, M06-C, backend structured API implementation, and final M07 remain pending.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host "[PENDING_INTEGRATION] M06-A frontend target skipped=$($webCounts.skipped)."
    exit 2
}

Write-MemoryStageReport `
    -RepositoryRoot $RepositoryRoot `
    -ReportPath $reportPath `
    -Stage 'M06_A' `
    -Status 'STAGE_VERIFIED' `
    -AssetCounts $assetCounts `
    -IntegratedCounts $combinedCounts `
    -Ready $true `
    -RequiredTargets $requiredTargets `
    -ReadinessMissing @() `
    -TargetTestsExpected 1 `
    -TargetTestsSkipped 0 `
    -RegressionGroups $regressionGroups `
    -Unverified @(
        'This stage validates only the M06-A frontend contract skeleton and Agent/Controller compatibility.',
        'M06-B, M06-C, backend structured API integration, a real browser, and final M07 are still pending.',
        'STAGE_VERIFIED is not Memory V1 AUTO_VERIFIED or human acceptance.'
    )
& $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
Write-Host '[STAGE_VERIFIED] M06-A frontend contract and Agent/Controller regression passed with target skipped=0.'
Write-Host '[PENDING_INTEGRATION] Complete M06-B/C and final Memory V1 acceptance remain pending.'
exit 0
