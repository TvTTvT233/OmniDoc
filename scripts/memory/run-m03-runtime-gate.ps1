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
$reportPath = Join-Path $ReportDirectory 'memory-m03a-runtime-stage-report.json'

$common = Join-Path $PSScriptRoot 'stage-gate-common.ps1'
$sensitiveCheck = Join-Path $PSScriptRoot 'assert-no-sensitive-data.ps1'
. $common

$scriptAssets = @(
    $common,
    $sensitiveCheck,
    (Join-Path $PSScriptRoot 'run-m02-gate.ps1'),
    (Join-Path $PSScriptRoot 'run-m03-runtime-gate.ps1'),
    (Join-Path $PSScriptRoot 'run-frontend-contract-gate.ps1')
)
Assert-MemoryPowerShellSyntax -Paths $scriptAssets

$cmdWrapper = Join-Path $PSScriptRoot 'run-m03-runtime-gate.cmd'
if (-not (Test-Path -LiteralPath $cmdWrapper -PathType Leaf)) {
    throw "M03-A Runtime gate wrapper is missing: $cmdWrapper"
}

$requiredTargets = @(
    'M03A_RUNTIME_STATE_MODEL',
    'M03A_RUNTIME_STATE_SERVICE',
    'M03A_RUNTIME_STATE_PERSISTENCE',
    'M03A_FIELD_WHITELIST',
    'M03A_BOUNDED_TTL',
    'M03A_CLEAR_INTERFACES',
    'M03A_RUNTIME_SPECIALIST_TESTS',
    'M03A_TWO_TARGET_TESTS_ACTIVE'
)
$m03bPending = @(
    'M03B_DELETION_CASCADE_PENDING',
    'M03B_OWNER_APPROVAL_PENDING'
)
$regressionGroups = @(
    'MemoryV1ExpectedContractTest:m03 core targets',
    'RuntimeState specialist tests',
    'ContextManager compatibility tests when present'
)

$assetRun = Invoke-MemoryMavenSelection `
    -RepositoryRoot $RepositoryRoot `
    -Name 'Memory M03-A Runtime gate asset' `
    -Selection 'MemoryV1ReportSchemaTest' `
    -ReportNamePattern 'TEST-com.javaee.aiservice.memory.acceptance.MemoryV1ReportSchemaTest.xml'
$assetCounts = $assetRun.Counts

if (-not $Integrated) {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M03_A' `
        -Status 'ASSET_VERIFIED' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing (@('INTEGRATED_MODE_NOT_REQUESTED') + $m03bPending) `
        -TargetTestsExpected 2 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'M03-A Runtime Core production behavior and target tests were not executed.',
            'M03-B deletion cascade and service-level continuation, approval, and ownership checks remain pending.',
            'No real Redis, MySQL, model, browser, remote Embedding, or Memory Qdrant collection was used.',
            'A stage asset report is not Memory V1 AUTO_VERIFIED.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) {
        throw "M03-A stage report boundary check failed with exit code $LASTEXITCODE"
    }
    Write-Host '[ASSET_VERIFIED] M03-A Runtime stage-gate scripts and report contract passed self-check.'
    Write-Host '[PENDING_INTEGRATION] M03-A Runtime Core was not evaluated; M03-B remains pending.'
    exit 0
}

$missing = [System.Collections.Generic.List[string]]::new()
$runtimeRoot = Join-Path $RepositoryRoot 'ai-service\src\main\java\com\javaee\aiservice\conversation\runtime'
$runtimeSources = @()
if (Test-Path -LiteralPath $runtimeRoot -PathType Container) {
    $runtimeSources = @(Get-ChildItem -LiteralPath $runtimeRoot -Recurse -File -Filter '*.java')
}
if ($runtimeSources.Count -eq 0) {
    foreach ($id in @(
        'M03A_RUNTIME_STATE_MODEL',
        'M03A_RUNTIME_STATE_SERVICE',
        'M03A_RUNTIME_STATE_PERSISTENCE',
        'M03A_FIELD_WHITELIST',
        'M03A_BOUNDED_TTL',
        'M03A_CLEAR_INTERFACES'
    )) {
        $missing.Add($id)
    }
} else {
    $runtimeText = ($runtimeSources | ForEach-Object {
        Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8
    }) -join "`n"

    if ($runtimeText -notmatch '(?m)\b(?:record|class)\s+RuntimeState\b') {
        $missing.Add('M03A_RUNTIME_STATE_MODEL')
    }
    if ($runtimeText -notmatch '(?m)\b(?:class|interface)\s+\w*RuntimeState\w*Service\b') {
        $missing.Add('M03A_RUNTIME_STATE_SERVICE')
    }
    if ($runtimeText -notmatch '(?m)\b(?:class|interface)\s+\w*RuntimeState\w*(?:Store|Repository)\b' -and
            $runtimeText -notmatch '(?m)\bRedisTemplate\s*<') {
        $missing.Add('M03A_RUNTIME_STATE_PERSISTENCE')
    }
    if ($runtimeText -notmatch '(?i)RuntimeStateField|allowedFields|supportedFields|fieldWhitelist|EnumSet\s*<') {
        $missing.Add('M03A_FIELD_WHITELIST')
    }
    if ($runtimeText -notmatch '(?i)Duration|expiresAt|\bttl\b' -or
            $runtimeText -notmatch '(?i)expire|expiration|ofMinutes|ofHours') {
        $missing.Add('M03A_BOUNDED_TTL')
    }
    if ($runtimeText -notmatch '(?i)clear(?:Field|Conversation|RuntimeState)|deleteByConversation|consume(?:Field|Once)|remove(?:Field|RuntimeState)') {
        $missing.Add('M03A_CLEAR_INTERFACES')
    }
}

$testRoot = Join-Path $RepositoryRoot 'ai-service\src\test\java'
$runtimeTests = @()
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    $runtimeTests = @(Get-ChildItem -LiteralPath $testRoot -Recurse -File -Filter '*RuntimeState*Test.java')
}
if ($runtimeTests.Count -eq 0) {
    $missing.Add('M03A_RUNTIME_SPECIALIST_TESTS')
}

if ($missing.Count -gt 0) {
    $readinessMissing = @($missing.ToArray()) + $m03bPending
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M03_A' `
        -Status 'PENDING_INTEGRATION' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing $readinessMissing `
        -TargetTestsExpected 2 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'Required M03-A RuntimeState model, service, Redis-backed persistence, whitelist, TTL, clear interfaces, or specialist tests are missing.',
            'The two M03-A target tests and Runtime/Context compatibility selection were not counted as passed.',
            'M03-B deletion cascade and service-level continuation, approval, and ownership checks remain pending.',
            'A pending stage report is not Memory V1 AUTO_VERIFIED.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host "[PENDING_INTEGRATION] Missing M03-A evidence: $($missing -join ', ')"
    Write-Host '[M03_B_PENDING] Deletion cascade and owner/approval service checks are outside M03-A.'
    exit 2
}

$selection = 'MemoryV1ExpectedContractTest,*RuntimeState*Test,*ContextManager*CompatibilityTest'
try {
    $integratedRun = Invoke-MemoryMavenSelection `
        -RepositoryRoot $RepositoryRoot `
        -Name 'Memory M03-A Runtime integrated stage' `
        -Selection $selection `
        -ReportNamePattern 'TEST-com.javaee.aiservice.*.xml'
} catch {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M03_A' `
        -Status 'FAILED' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $true `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing (@('M03A_TEST_SELECTION_FAILED') + $m03bPending) `
        -TargetTestsExpected 2 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'The M03-A Runtime Maven stage selection failed; see the local console output.',
            'M03-B remains pending and no final Memory V1 acceptance conclusion was produced.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    throw
}

$activeMethods = @(
    'm03RuntimeStateUsesWhitelistAndBoundedTtl',
    'm03RuntimeStateDoesNotLeakAcrossConversations'
)
$deferredMethods = @(
    'm03ConversationDeletionCascadesRuntimeDerivatives',
    'm03ContinuationAndApprovalCancellationEnforceOwnershipInsideService'
)
$expectedReport = $integratedRun.Reports |
    Where-Object { $_.Name -eq 'TEST-com.javaee.aiservice.memory.MemoryV1ExpectedContractTest.xml' } |
    Select-Object -First 1
$targetSkipped = 0
$targetMissing = [System.Collections.Generic.List[string]]::new()
if ($null -eq $expectedReport) {
    foreach ($method in $activeMethods) { $targetMissing.Add("M03A_TARGET_NOT_RUN:$method") }
    foreach ($method in $deferredMethods) { $targetMissing.Add("M03B_TARGET_INVENTORY_MISSING:$method") }
} else {
    [xml]$expectedXml = Get-Content -LiteralPath $expectedReport.FullName -Raw -Encoding utf8
    $testCases = @($expectedXml.testsuite.testcase)
    foreach ($method in $activeMethods) {
        $testCase = $testCases | Where-Object { $_.name -eq $method } | Select-Object -First 1
        if ($null -eq $testCase) {
            $targetMissing.Add("M03A_TARGET_NOT_RUN:$method")
            continue
        }
        if (@($testCase.ChildNodes | Where-Object { $_.LocalName -eq 'skipped' }).Count -gt 0) {
            $targetSkipped++
        }
    }
    foreach ($method in $deferredMethods) {
        if ($null -eq ($testCases | Where-Object { $_.name -eq $method } | Select-Object -First 1)) {
            $targetMissing.Add("M03B_TARGET_INVENTORY_MISSING:$method")
        }
    }
}

if ($targetMissing.Count -gt 0 -or $targetSkipped -gt 0) {
    $pendingReasons = [System.Collections.Generic.List[string]]::new()
    foreach ($reason in $targetMissing) { $pendingReasons.Add($reason) }
    if ($targetSkipped -gt 0) { $pendingReasons.Add('M03A_TARGET_TESTS_SKIPPED') }
    foreach ($reason in $m03bPending) { $pendingReasons.Add($reason) }
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M03_A' `
        -Status 'PENDING_INTEGRATION' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $integratedRun.Counts `
        -Ready $false `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing $pendingReasons.ToArray() `
        -TargetTestsExpected 2 `
        -TargetTestsSkipped $targetSkipped `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'Both named M03-A target tests must execute with skipped equal to zero.',
            'The two M03-B target methods must remain visible but may stay Disabled during M03-A.',
            'M03-B deletion cascade and service-level continuation, approval, and ownership checks remain pending.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host "[PENDING_INTEGRATION] M03-A target tests missing=$($targetMissing.Count), skipped=$targetSkipped."
    Write-Host '[M03_B_PENDING] Deletion cascade and owner/approval service checks are outside M03-A.'
    exit 2
}

Write-MemoryStageReport `
    -RepositoryRoot $RepositoryRoot `
    -ReportPath $reportPath `
    -Stage 'M03_A' `
    -Status 'STAGE_VERIFIED' `
    -AssetCounts $assetCounts `
    -IntegratedCounts $integratedRun.Counts `
    -Ready $true `
    -RequiredTargets $requiredTargets `
    -ReadinessMissing $m03bPending `
    -TargetTestsExpected 2 `
    -TargetTestsSkipped 0 `
    -RegressionGroups $regressionGroups `
    -Unverified @(
        'This stage verifies only M03-A Runtime Core whitelist, TTL, clear interfaces, and cross-conversation isolation.',
        'M03-B deletion cascade and service-level continuation, approval, and ownership checks remain pending and may still be Disabled.',
        'No real Redis outage, MySQL, model, browser, remote Embedding, complete M03, or final M07 gate was exercised.',
        'STAGE_VERIFIED is not Memory V1 AUTO_VERIFIED or human acceptance.'
    )
& $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
Write-Host '[STAGE_VERIFIED] M03-A Runtime Core targets ran with skipped=0 and specialist/compatibility tests passed.'
Write-Host '[M03_B_PENDING] Deletion cascade and owner/approval service checks remain pending.'
Write-Host '[PENDING_INTEGRATION] Complete M03-B through M07 and final Memory V1 acceptance remain pending.'
exit 0
