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
$reportPath = Join-Path $ReportDirectory 'memory-m02-stage-report.json'

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

$cmdWrapper = Join-Path $PSScriptRoot 'run-m02-gate.cmd'
if (-not (Test-Path -LiteralPath $cmdWrapper -PathType Leaf)) {
    throw "M02 gate wrapper is missing: $cmdWrapper"
}

$requiredTargets = @(
    'M02_CONVERSATION_MODEL',
    'M02_CONVERSATION_STORE',
    'M02_SCHEMA_INITIALIZER',
    'M02_SPRING_JDBC',
    'M02_MYSQL_DRIVER',
    'M02_FOUR_TARGET_TESTS_ACTIVE'
)
$regressionGroups = @(
    'MemoryV1ExpectedContractTest:m02*',
    'M02 persistence/store/schema tests',
    'Legacy import/delete and ConversationManager compatibility tests',
    'AgentExecutionServiceTest',
    'AgentControllerTest'
)
$assetRun = Invoke-MemoryMavenSelection `
    -RepositoryRoot $RepositoryRoot `
    -Name 'Memory M02 gate asset' `
    -Selection 'MemoryV1ReportSchemaTest' `
    -ReportNamePattern 'TEST-com.javaee.aiservice.memory.acceptance.MemoryV1ReportSchemaTest.xml'
$assetCounts = $assetRun.Counts

if (-not $Integrated) {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M02' `
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
            'M02 production persistence and target tests were not executed.',
            'No real MySQL, Redis, model, remote Embedding, browser, or Memory Qdrant collection was used.',
            'A stage asset report is not Memory V1 AUTO_VERIFIED.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) {
        throw "M02 stage report boundary check failed with exit code $LASTEXITCODE"
    }
    Write-Host '[ASSET_VERIFIED] M02 stage-gate scripts and report contract passed self-check.'
    Write-Host '[PENDING_INTEGRATION] M02 production behavior was not evaluated.'
    exit 0
}

$missing = [System.Collections.Generic.List[string]]::new()
$modelRoot = Join-Path $RepositoryRoot 'ai-service\src\main\java\com\javaee\aiservice\conversation\model'
$storeRoot = Join-Path $RepositoryRoot 'ai-service\src\main\java\com\javaee\aiservice\conversation\store'
if (-not (Test-Path -LiteralPath $modelRoot -PathType Container) -or
        @(Get-ChildItem -LiteralPath $modelRoot -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue).Count -eq 0) {
    $missing.Add('M02_CONVERSATION_MODEL')
}
if (-not (Test-Path -LiteralPath $storeRoot -PathType Container) -or
        @(Get-ChildItem -LiteralPath $storeRoot -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue).Count -eq 0) {
    $missing.Add('M02_CONVERSATION_STORE')
}

$mainJavaRoot = Join-Path $RepositoryRoot 'ai-service\src\main\java'
$initializerFound = $false
if (Test-Path -LiteralPath $mainJavaRoot -PathType Container) {
    foreach ($source in Get-ChildItem -LiteralPath $mainJavaRoot -Recurse -File -Filter '*.java') {
        if ((Get-Content -LiteralPath $source.FullName -Raw -Encoding utf8) -match 'MemorySchemaInitializer') {
            $initializerFound = $true
            break
        }
    }
}
if (-not $initializerFound) {
    $missing.Add('M02_SCHEMA_INITIALIZER')
}

$pomText = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'ai-service\pom.xml') -Raw -Encoding utf8
if ($pomText -notmatch 'spring-(?:boot-starter-)?jdbc') {
    $missing.Add('M02_SPRING_JDBC')
}
if ($pomText -notmatch '(?:mysql-connector-j|mysql-connector-java)') {
    $missing.Add('M02_MYSQL_DRIVER')
}

if ($missing.Count -gt 0) {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M02' `
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
            'Required M02 production source groups or persistence dependencies are missing.',
            'The four M02 target tests and Agent/Controller regression were not counted as passed.',
            'A pending stage report is not Memory V1 AUTO_VERIFIED.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host "[PENDING_INTEGRATION] Missing M02 evidence: $($missing -join ', ')"
    exit 2
}

$selection = 'MemoryV1ExpectedContractTest,*Conversation*Persistence*Test,*Conversation*Store*Test,*MemorySchemaInitializerTest,LegacyConversationImporterTest,ConversationManagerCharacterizationTest,AgentConversationMemoryCharacterizationTest,AgentControllerConversationBaselineTest,AgentExecutionServiceTest,AgentControllerTest'
try {
    $integratedRun = Invoke-MemoryMavenSelection `
        -RepositoryRoot $RepositoryRoot `
        -Name 'Memory M02 integrated stage' `
        -Selection $selection `
        -ReportNamePattern 'TEST-com.javaee.aiservice.*.xml'
} catch {
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M02' `
        -Status 'FAILED' `
        -AssetCounts $assetCounts `
        -IntegratedCounts $null `
        -Ready $true `
        -RequiredTargets $requiredTargets `
        -ReadinessMissing @('M02_TEST_SELECTION_FAILED') `
        -TargetTestsExpected 4 `
        -TargetTestsSkipped $null `
        -RegressionGroups $regressionGroups `
        -Unverified @(
            'The M02 Maven stage selection failed; see the local console output.',
            'No final Memory V1 acceptance conclusion was produced.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    throw
}

$expectedMethods = @(
    'm02PersistsStructuredMessagesWithMonotonicSequence',
    'm02RetriesDoNotDuplicateUserOrAssistantMessages',
    'm02HistorySurvivesServiceRestartAndRedisExpiry',
    'm02ConversationAccessIsOwnerIsolated'
)
$expectedReport = $integratedRun.Reports |
    Where-Object { $_.Name -eq 'TEST-com.javaee.aiservice.memory.MemoryV1ExpectedContractTest.xml' } |
    Select-Object -First 1
$targetSkipped = 0
$targetMissing = [System.Collections.Generic.List[string]]::new()
if ($null -eq $expectedReport) {
    foreach ($method in $expectedMethods) { $targetMissing.Add("M02_TARGET_NOT_RUN:$method") }
} else {
    [xml]$expectedXml = Get-Content -LiteralPath $expectedReport.FullName -Raw -Encoding utf8
    $testCases = @($expectedXml.testsuite.testcase)
    foreach ($method in $expectedMethods) {
        $testCase = $testCases | Where-Object { $_.name -eq $method } | Select-Object -First 1
        if ($null -eq $testCase) {
            $targetMissing.Add("M02_TARGET_NOT_RUN:$method")
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
    if ($targetSkipped -gt 0) { $pendingReasons.Add('M02_TARGET_TESTS_SKIPPED') }
    Write-MemoryStageReport `
        -RepositoryRoot $RepositoryRoot `
        -ReportPath $reportPath `
        -Stage 'M02' `
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
            'All four named M02 target tests must execute with skipped equal to zero.',
            'M02 stage verification cannot promote the final Memory V1 status.'
        )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host "[PENDING_INTEGRATION] M02 target tests missing=$($targetMissing.Count), skipped=$targetSkipped."
    exit 2
}

Write-MemoryStageReport `
    -RepositoryRoot $RepositoryRoot `
    -ReportPath $reportPath `
    -Stage 'M02' `
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
        'This stage did not run a real MySQL restart, Redis outage, model, browser, remote Embedding, or final M07 gate.',
        'STAGE_VERIFIED is not Memory V1 AUTO_VERIFIED or human acceptance.'
    )
& $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
Write-Host '[STAGE_VERIFIED] M02 targets ran with skipped=0 and Agent/Controller regression passed.'
Write-Host '[PENDING_INTEGRATION] M03-M07 and final Memory V1 acceptance remain pending.'
exit 0
