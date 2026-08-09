[CmdletBinding()]
param(
    [switch]$Integrated,
    [string]$RepositoryRoot,
    [string]$ProviderClass = $env:QV04_ACCEPTANCE_PROVIDER_CLASS,
    [string]$CollectionPrefix = $env:QV04_TEST_COLLECTION_PREFIX,
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
$sensitiveCheck = Join-Path $PSScriptRoot 'assert-no-sensitive-data.ps1'
$collectionCheck = Join-Path $PSScriptRoot 'assert-test-collection-boundary.ps1'
$readinessCheck = Join-Path $PSScriptRoot 'assert-integration-readiness.ps1'
if ([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $ReportDirectory = Join-Path $RepositoryRoot 'ai-service\target\qv04-acceptance'
}
$ReportDirectory = [System.IO.Path]::GetFullPath($ReportDirectory)
[System.IO.Directory]::CreateDirectory($ReportDirectory) | Out-Null

if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw "Maven wrapper not found: $wrapper"
}
if (-not (Test-Path -LiteralPath $settings -PathType Leaf)) {
    throw "Maven settings not found: $settings"
}

if ([string]::IsNullOrWhiteSpace($CollectionPrefix)) {
    $CollectionPrefix = 'qv04_test_'
}
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ').ToLowerInvariant()
$testCollection = "$CollectionPrefix$runId"
$testAlias = "${testCollection}_alias"
$env:QV04_TEST_COLLECTION_PREFIX = $CollectionPrefix
$env:QV04_TEST_COLLECTION = $testCollection
$env:QV04_TEST_COLLECTION_ALIAS = $testAlias
$env:QV04_REMOTE_EMBEDDING_DISABLED = 'true'

$branch = (& git -C $RepositoryRoot branch --show-current).Trim()
$head = (& git -C $RepositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read Git state'
}

Write-Host "QV04 acceptance root: $RepositoryRoot"
Write-Host "Git branch/head: $branch / $head"
Write-Host "Synthetic fixture Collection: $testCollection"
Write-Host 'Remote Embedding: disabled; the QV04 fixture uses deterministic SHA-256 vectors.'

function Invoke-Qv04MavenTests {
    param(
        [string]$Name,
        [string]$Selection,
        [string[]]$Properties = @()
    )

    $started = (Get-Date).AddSeconds(-1)
    $arguments = @(
        '-s', $settings,
        '-pl', 'ai-service',
        '-am',
        "-Dtest=$Selection",
        '-Dsurefire.failIfNoSpecifiedTests=false'
    ) + $Properties + @('test')

    Write-Host "Running $Name Maven selection: $Selection"
    & $wrapper @arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "$Name Maven selection failed with exit code $LASTEXITCODE"
    }

    $surefireRoot = Join-Path $RepositoryRoot 'ai-service\target\surefire-reports'
    $xmlReports = @()
    if (Test-Path -LiteralPath $surefireRoot -PathType Container) {
        $xmlReports = @(Get-ChildItem -LiteralPath $surefireRoot -File -Filter 'TEST-*.xml' |
            Where-Object { $_.LastWriteTime -ge $started })
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

function Get-MachineEvidence {
    $processor = $env:PROCESSOR_IDENTIFIER
    $memoryBytes = $null
    $javaLine = ''
    try {
        $computer = Get-CimInstance -ClassName Win32_ComputerSystem -ErrorAction Stop
        if ($computer.Model) {
            $processor = "$processor; $($computer.Model)"
        }
        $memoryBytes = [long]$computer.TotalPhysicalMemory
    } catch {
        # The report still records the available process environment when CIM is unavailable.
    }
    $previousErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaLine = ((& java -version 2>&1 | Select-Object -First 1) -join '')
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    return [ordered]@{
        os = [System.Environment]::OSVersion.VersionString
        processor = $processor
        physicalMemoryBytes = $memoryBytes
        java = $javaLine
    }
}

function Read-JsonOrNull {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    return Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json
}

function Save-Qv04RunReport {
    param(
        [string]$Status,
        [object]$AssetCounts,
        [object]$IntegratedCounts,
        [string[]]$Unverified
    )

    $machine = Get-MachineEvidence
    $baselineMetrics = $null
    $qdrantMetrics = $null
    $shadowMetrics = $null
    $migrationMetrics = $null
    if ($Status -eq 'AUTO_VERIFIED') {
        $baselineMetrics = Read-JsonOrNull (Join-Path $ReportDirectory 'baseline-metrics.json')
        $qdrantMetrics = Read-JsonOrNull (Join-Path $ReportDirectory 'qdrant-metrics.json')
        $shadowMetrics = Read-JsonOrNull (Join-Path $ReportDirectory 'shadow-success.json')
        $migrationMetrics = Read-JsonOrNull (Join-Path $ReportDirectory 'migration.json')
    }

    $assetRun = 0
    $assetFailures = 0
    $assetErrors = 0
    $assetSkipped = 0
    if ($null -ne $AssetCounts) {
        $assetRun = [int]$AssetCounts.run
        $assetFailures = [int]$AssetCounts.failures
        $assetErrors = [int]$AssetCounts.errors
        $assetSkipped = [int]$AssetCounts.skipped
    }
    $integratedRun = 0
    $integratedFailures = 0
    $integratedErrors = 0
    $integratedSkipped = 0
    if ($null -ne $IntegratedCounts) {
        $integratedRun = [int]$IntegratedCounts.run
        $integratedFailures = [int]$IntegratedCounts.failures
        $integratedErrors = [int]$IntegratedCounts.errors
        $integratedSkipped = [int]$IntegratedCounts.skipped
    }
    $failures = $assetFailures + $integratedFailures
    $errors = $assetErrors + $integratedErrors
    $skipped = $assetSkipped + $integratedSkipped

    $recallGate = $null
    $tenantLeakage = $null
    $deleteResidue = $null
    $migrationDifference = $null
    if ($Status -eq 'AUTO_VERIFIED') {
        $recallGate = $qdrantMetrics.recallAt5 -ge $baselineMetrics.recallAt5
        $tenantLeakage = [int]$baselineMetrics.tenantLeakage + [int]$qdrantMetrics.tenantLeakage
        $deleteResidue = [int]$baselineMetrics.deleteResidue + [int]$qdrantMetrics.deleteResidue
        $migrationDifference = [long]$migrationMetrics.sourceCount - [long]$migrationMetrics.targetCount
    }
    $shadowOverlap = $null
    if ($null -ne $shadowMetrics) {
        $shadowOverlap = $shadowMetrics.overlapAtK
    }

    $report = [ordered]@{
        schemaVersion = 1
        status = $Status
        manualStatus = 'MANUAL_PENDING'
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        fixture = [ordered]@{
            id = 'qv04-synthetic-vector-acceptance-v1'
            synthetic = $true
            remoteEmbedding = $false
            users = 3
            knowledgeBasesPerUser = 2
            segmentsPerKnowledgeBase = 10
            records = 60
            dimension = 16
        }
        environment = [ordered]@{
            repositoryRoot = $RepositoryRoot
            branch = $branch
            head = $head
            os = $machine.os
            processor = $machine.processor
            physicalMemoryBytes = $machine.physicalMemoryBytes
            java = $machine.java
            externalNetworkIncluded = $false
            testCollection = $testCollection
        }
        tests = [ordered]@{
            run = $assetRun + $integratedRun
            failures = $failures
            errors = $errors
            skipped = $skipped
            asset = $AssetCounts
            integrated = $IntegratedCounts
        }
        gates = [ordered]@{
            qdrantRecallAt5AtLeastBaseline = $recallGate
            tenantLeakage = $tenantLeakage
            deleteResidue = $deleteResidue
            migrationCountDifference = $migrationDifference
        }
        metrics = [ordered]@{
            baseline = $baselineMetrics
            qdrant = $qdrantMetrics
            shadowOverlapAtK = $shadowOverlap
            migration = $migrationMetrics
        }
        unverified = @($Unverified)
    }
    $reportPath = Join-Path $ReportDirectory 'qv04-run-report.json'
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Host "QV04 machine-readable report: $reportPath"
}

$assetSelection = 'Qv04FixtureTest,Qv04CollectionBoundaryTest,Qv04MetricsTest,Qv04ReportSchemaTest,Qv04HarnessSelfTest,VectorContractTest,AgentExecutionServiceTest,AgentControllerTest,KnowledgeIndexAgentTest,DocumentSegmenterTest,*Mcp*Test,McpAgentRoutingTest,McpToolCatalogAdapterTest,McpToolCatalogEndpointTest'
$assetCounts = Invoke-Qv04MavenTests -Name 'QV04 asset' -Selection $assetSelection

& $sensitiveCheck -RepositoryRoot $RepositoryRoot
if ($LASTEXITCODE -ne 0) {
    throw "Sensitive-data check failed with exit code $LASTEXITCODE"
}
& $collectionCheck -RepositoryRoot $RepositoryRoot -CollectionPrefix $CollectionPrefix
if ($LASTEXITCODE -ne 0) {
    throw "Collection boundary check failed with exit code $LASTEXITCODE"
}

if (-not $Integrated) {
    Save-Qv04RunReport -Status 'ASSET_VERIFIED' -AssetCounts $assetCounts -IntegratedCounts $null -Unverified @(
        'QV01 local backend is not present on this independent branch.',
        'QV02 Qdrant backend and a controlled local Qdrant service were not run.',
        'QV03 routing, Shadow, and migration implementations are not present.',
        'Real Qdrant persistence, two-client visibility, quality, latency, throughput, startup, and memory remain unverified.',
        'Current-branch Agent/RAG/MCP regression passed but must be rerun after QV01-QV03 integration.',
        'Remote Embedding and real user data are intentionally outside this acceptance run.'
    )
    Write-Host '[PASS] QV04 fixture, harness, metrics, report, sensitive-data, and Collection-boundary assets passed.'
    Write-Host '[PENDING_INTEGRATION] QV01-QV03 production implementations are required for the hard gates.'
    exit 0
}

& $readinessCheck -RepositoryRoot $RepositoryRoot -ProviderClass $ProviderClass
$readinessExit = $LASTEXITCODE
if ($readinessExit -eq 2) {
    Save-Qv04RunReport -Status 'PENDING_INTEGRATION' -AssetCounts $assetCounts -IntegratedCounts $null -Unverified @(
        'QV01-QV03 source groups or the QV04 integrated provider are missing.',
        'No production backend, Qdrant lifecycle, Shadow, migration, or cross-feature regression gate was counted as passed.',
        'Remote Embedding and real user data were not used.'
    )
    exit 2
}
if ($readinessExit -ne 0) {
    throw "Integration readiness check failed with exit code $readinessExit"
}

$integratedSelection = 'Qv04IntegratedGateTest,VectorStoreBaselineCharacterizationTest,*Local*Vector*Test,*VectorBackend*Test,*Qdrant*Test,*Shadow*Test,*Migration*Test'
$integratedProperties = @(
    "-Dqv04.acceptance.providerClass=$ProviderClass",
    "-Dqv04.test.collection=$testCollection",
    "-Dqv04.report.dir=$ReportDirectory",
    '-Dqv04.remoteEmbedding=false'
)
$integratedCounts = Invoke-Qv04MavenTests -Name 'QV04 integrated' -Selection $integratedSelection -Properties $integratedProperties

Save-Qv04RunReport -Status 'AUTO_VERIFIED' -AssetCounts $assetCounts -IntegratedCounts $integratedCounts -Unverified @(
    'Remote Embedding and real user data were intentionally not used.',
    'Single-node local results are not production capacity or high-availability evidence.',
    'User manual acceptance remains MANUAL_PENDING.'
)
Write-Host '[PASS] QV04 integrated hard gates and Agent/RAG/MCP regression selection completed.'
Write-Host '[MANUAL_PENDING] User acceptance must still follow docs/qdrant/06-HUMAN_ACCEPTANCE.md.'
