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

$wrapper = Join-Path $RepositoryRoot 'mvnw.cmd'
$settings = Join-Path $RepositoryRoot '.mvn\settings-local.xml'
$fixturePath = Join-Path $RepositoryRoot 'ai-service\src\test\resources\memory\memory-v1-fixture.json'
$sensitiveCheck = Join-Path $PSScriptRoot 'assert-no-sensitive-data.ps1'
$readinessCheck = Join-Path $PSScriptRoot 'assert-integration-readiness.ps1'
if ([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $ReportDirectory = Join-Path $RepositoryRoot 'ai-service\target\memory-v1-acceptance'
}
$ReportDirectory = [System.IO.Path]::GetFullPath($ReportDirectory)
[System.IO.Directory]::CreateDirectory($ReportDirectory) | Out-Null
$reportPath = Join-Path $ReportDirectory 'memory-v1-report.json'

foreach ($requiredFile in @($wrapper, $settings, $fixturePath, $sensitiveCheck, $readinessCheck)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Memory V1 acceptance asset is missing: $requiredFile"
    }
}

$branch = (& git -C $RepositoryRoot branch --show-current).Trim()
$head = (& git -C $RepositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read Git state'
}
$fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding utf8 | ConvertFrom-Json

Write-Host "Memory V1 acceptance root: $RepositoryRoot"
Write-Host "Git branch/head: $branch / $head"
Write-Host 'Fixture boundary: synthetic, local, no real model, no remote Embedding.'

function Get-ToolVersion {
    param([string]$Command, [string[]]$Arguments)

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $line = ((& $Command @Arguments 2>&1 | Select-Object -First 1) -join '')
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($line)) {
            return 'NOT_AVAILABLE'
        }
        return $line.Trim()
    } catch {
        return 'NOT_AVAILABLE'
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Invoke-MemoryMavenTests {
    param(
        [string]$Name,
        [string]$Selection,
        [string]$ReportNamePattern
    )

    $started = (Get-Date).AddSeconds(-2)
    Write-Host "Running $Name Maven selection: $Selection"
    & $wrapper -s $settings -pl ai-service -am "-Dtest=$Selection" '-Dsurefire.failIfNoSpecifiedTests=false' test | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "$Name Maven selection failed with exit code $LASTEXITCODE"
    }

    $surefireRoot = Join-Path $RepositoryRoot 'ai-service\target\surefire-reports'
    $xmlReports = @()
    if (Test-Path -LiteralPath $surefireRoot -PathType Container) {
        $xmlReports = @(Get-ChildItem -LiteralPath $surefireRoot -File -Filter 'TEST-*.xml' |
            Where-Object { $_.Name -like $ReportNamePattern -and $_.LastWriteTime -ge $started })
    }
    if ($xmlReports.Count -eq 0) {
        throw "$Name produced no fresh Surefire XML reports"
    }

    $counts = [ordered]@{ run = 0; failures = 0; errors = 0; skipped = 0; reports = $xmlReports.Count }
    foreach ($report in $xmlReports) {
        [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw -Encoding utf8
        $suite = $xml.testsuite
        $counts.run += [int]$suite.tests
        $counts.failures += [int]$suite.failures
        $counts.errors += [int]$suite.errors
        $counts.skipped += [int]$suite.skipped
    }
    return [pscustomobject]$counts
}

function Save-MemoryReport {
    param(
        [string]$Status,
        [object]$AssetCounts,
        [object]$IntegratedCounts,
        [string[]]$Unverified
    )

    $assetRun = if ($null -eq $AssetCounts) { 0 } else { [int]$AssetCounts.run }
    $assetFailures = if ($null -eq $AssetCounts) { 0 } else { [int]$AssetCounts.failures }
    $assetErrors = if ($null -eq $AssetCounts) { 0 } else { [int]$AssetCounts.errors }
    $assetSkipped = if ($null -eq $AssetCounts) { 0 } else { [int]$AssetCounts.skipped }
    $integratedRun = if ($null -eq $IntegratedCounts) { 0 } else { [int]$IntegratedCounts.run }
    $integratedFailures = if ($null -eq $IntegratedCounts) { 0 } else { [int]$IntegratedCounts.failures }
    $integratedErrors = if ($null -eq $IntegratedCounts) { 0 } else { [int]$IntegratedCounts.errors }
    $integratedSkipped = if ($null -eq $IntegratedCounts) { 0 } else { [int]$IntegratedCounts.skipped }

    $report = [ordered]@{
        schemaVersion = 1
        status = $Status
        manualStatus = 'NOT_READY'
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        fixture = [ordered]@{
            id = $fixture.id
            synthetic = $true
            messageCount = @($fixture.messages).Count
            summarySchemaVersion = $fixture.expectedSummary.schemaVersion
        }
        environment = [ordered]@{
            branch = $branch
            head = $head
            os = [System.Environment]::OSVersion.VersionString
            java = Get-ToolVersion -Command 'java' -Arguments @('-version')
            node = Get-ToolVersion -Command 'node' -Arguments @('--version')
            externalNetworkIncluded = $false
        }
        boundaries = [ordered]@{
            mysqlConnected = $false
            redisConnected = $false
            realModel = $false
            remoteEmbedding = $false
            embeddingCalls = 0
            memoryQdrantCollections = 0
        }
        tests = [ordered]@{
            run = $assetRun + $integratedRun
            failures = $assetFailures + $integratedFailures
            errors = $assetErrors + $integratedErrors
            skipped = $assetSkipped + $integratedSkipped
            asset = $AssetCounts
            integrated = $IntegratedCounts
        }
        gates = [ordered]@{
            messageLoss = $null
            duplicateSequence = $null
            coverageGap = $null
            coverageDuplicate = $null
            staleSummaryOverwrite = $null
            unsupportedConfirmedItems = $null
            successfulCrossUserAccess = $null
            deleteResidue = $null
            tokenOverBudget = $null
            modelCallPathsCovered = $null
            requiredModelCallPaths = 7
        }
        contentSafety = [ordered]@{
            containsConversationContent = $false
            containsSummaryContent = $false
            containsDocumentContent = $false
            containsPromptContent = $false
            containsSelectedText = $false
            containsToolResultContent = $false
            containsCredential = $false
        }
        metrics = [ordered]@{
            tokenBudget = $null
            summaryCalls = $null
            summaryFailures = $null
        }
        unverified = @($Unverified)
    }

    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Host "Memory V1 machine-readable report: $reportPath"
}

$assetSelection = 'MemoryV1FixtureTest,MemoryV1SummarySchemaTest,MemoryV1ReportSchemaTest'
$assetCounts = Invoke-MemoryMavenTests `
    -Name 'Memory V1 asset' `
    -Selection $assetSelection `
    -ReportNamePattern 'TEST-com.javaee.aiservice.memory.acceptance.MemoryV1*.xml'

& $sensitiveCheck -RepositoryRoot $RepositoryRoot
if ($LASTEXITCODE -ne 0) {
    throw "Memory V1 sensitive-data check failed with exit code $LASTEXITCODE"
}

if (-not $Integrated) {
    Save-MemoryReport -Status 'ASSET_VERIFIED' -AssetCounts $assetCounts -IntegratedCounts $null -Unverified @(
        'Conversation, Message, and Summary MySQL persistence were not exercised.',
        'Runtime State lifecycle and deletion cascade were not exercised.',
        'MemoryContextBuilder and all seven model-call paths were not exercised.',
        'Structured rolling summary generation and CAS were not exercised.',
        'No real model, MySQL, Redis, browser, remote Embedding, or Memory Qdrant collection was used.',
        'M01 asset verification is not Memory V1 AUTO_VERIFIED.'
    )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) {
        throw "Memory V1 report boundary check failed with exit code $LASTEXITCODE"
    }
    Write-Host '[ASSET_VERIFIED] Memory V1 fixture, schemas, asset tests, and sensitive-data boundary passed.'
    Write-Host '[PENDING_INTEGRATION] Production Memory V1 behavior is not implemented or verified.'
    exit 0
}

& $readinessCheck -RepositoryRoot $RepositoryRoot
$readinessExit = $LASTEXITCODE
if ($readinessExit -eq 2) {
    Save-MemoryReport -Status 'PENDING_INTEGRATION' -AssetCounts $assetCounts -IntegratedCounts $null -Unverified @(
        'Required M02-M06 production source groups or the M07 integrated gate are missing.',
        'No persistence, concurrency, isolation, deletion, context, summary, frontend, or real-model gate was counted as passed.',
        'M01 asset verification is not Memory V1 AUTO_VERIFIED.'
    )
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host '[PENDING_INTEGRATION] Integrated Memory V1 acceptance cannot run on the current source tree.'
    exit 2
}
if ($readinessExit -ne 0) {
    throw "Memory V1 integration readiness check failed with exit code $readinessExit"
}

$integratedSelection = 'MemoryV1IntegratedGateTest,*Memory*Test,AgentExecutionServiceTest,AgentControllerTest'
$integratedCounts = Invoke-MemoryMavenTests `
    -Name 'Memory V1 integrated candidate' `
    -Selection $integratedSelection `
    -ReportNamePattern 'TEST-com.javaee.aiservice.*.xml'
Save-MemoryReport -Status 'PENDING_INTEGRATION' -AssetCounts $assetCounts -IntegratedCounts $integratedCounts -Unverified @(
    'The M01 runner does not reconcile every P0/P1 gate and therefore cannot emit AUTO_VERIFIED.',
    'M07 must replace this review boundary after all production tasks are integrated.',
    'User manual acceptance remains unavailable.'
)
& $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
Write-Host '[REVIEW_REQUIRED] Candidate tests ran, but M01-AC is not authorized to declare AUTO_VERIFIED.'
exit 2
