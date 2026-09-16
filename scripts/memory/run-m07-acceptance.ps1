[CmdletBinding()]
param(
    [switch]$Integrated,
    [string]$RepositoryRoot,
    [string]$ReportDirectory
)

Set-StrictMode -Version Latest
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
[System.IO.Directory]::CreateDirectory($ReportDirectory) | Out-Null

$wrapper = Join-Path $RepositoryRoot 'mvnw.cmd'
$settings = Join-Path $RepositoryRoot '.mvn\settings-local.xml'
$fixturePath = Join-Path $RepositoryRoot 'ai-service\src\test\resources\memory\memory-v1-fixture.json'
$sensitiveCheck = Join-Path $PSScriptRoot 'assert-no-sensitive-data.ps1'
$readinessCheck = Join-Path $PSScriptRoot 'assert-integration-readiness.ps1'
$surefireRoot = Join-Path $RepositoryRoot 'ai-service\target\surefire-reports'
$reportPath = Join-Path $ReportDirectory 'memory-v1-report.json'
$readinessPath = Join-Path $ReportDirectory 'memory-v1-readiness.json'
$evidencePath = Join-Path $ReportDirectory 'memory-v1-integrated-evidence.json'
$frontendContractReportPath = Join-Path $ReportDirectory 'memory-v1-m06-frontend-contract-report.json'
$frontendBaselineReportPath = Join-Path $ReportDirectory 'memory-v1-m06-frontend-baseline-report.json'
$frontendUiReportPath = Join-Path $ReportDirectory 'memory-v1-m06-frontend-ui-report.json'

foreach ($requiredFile in @($wrapper, $settings, $fixturePath, $sensitiveCheck, $readinessCheck)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Memory V1 acceptance asset is missing: $requiredFile"
    }
}

$fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding utf8 | ConvertFrom-Json
$branch = (& git -C $RepositoryRoot branch --show-current).Trim()
$head = (& git -C $RepositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[a-f0-9]{40}$') {
    throw 'Unable to read a valid Git branch/HEAD'
}

function Get-ToolVersion {
    param([string]$Command, [string[]]$Arguments)

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Command @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $line = (($output | Select-Object -First 1) -join '')
        if ($exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($line)) {
            return 'NOT_AVAILABLE'
        }
        return $line.Trim()
    } catch {
        return 'NOT_AVAILABLE'
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Get-DirtyWorktreeEntries {
    $entries = @(& git -C $RepositoryRoot status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect Git worktree state'
    }
    return @($entries | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Assert-SafeSurefireRoot {
    $expected = [System.IO.Path]::GetFullPath(
        (Join-Path $RepositoryRoot 'ai-service\target\surefire-reports'))
    $actual = [System.IO.Path]::GetFullPath($surefireRoot)
    if (-not $actual.Equals($expected, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected Surefire path: $actual"
    }
}

function Remove-StaleSurefireXml {
    Assert-SafeSurefireRoot
    if (-not (Test-Path -LiteralPath $surefireRoot -PathType Container)) {
        return
    }
    foreach ($file in @(Get-ChildItem -LiteralPath $surefireRoot -File -Filter 'TEST-*.xml')) {
        Remove-Item -LiteralPath $file.FullName -Force
    }
}

function Remove-ExactGeneratedFile {
    param([string]$Path)

    $absolute = [System.IO.Path]::GetFullPath($Path)
    $reportRoot = $ReportDirectory.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
        [System.IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($reportRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove generated file outside the report directory: $absolute"
    }
    if (Test-Path -LiteralPath $absolute -PathType Leaf) {
        Remove-Item -LiteralPath $absolute -Force
    }
}

function Get-TestSourcePath {
    param([string]$FullyQualifiedClass)

    $relative = $FullyQualifiedClass.Replace('.', [System.IO.Path]::DirectorySeparatorChar) + '.java'
    return Join-Path $RepositoryRoot (Join-Path 'ai-service\src\test\java' $relative)
}

function Get-MissingExactTestClasses {
    param([object[]]$Classes)

    $missing = [System.Collections.Generic.List[string]]::new()
    foreach ($class in $Classes) {
        $source = Get-TestSourcePath -FullyQualifiedClass $class.Fqcn
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            $missing.Add("TEST_CLASS_MISSING:$($class.Fqcn)")
            continue
        }
        $text = Get-Content -LiteralPath $source -Raw -Encoding utf8
        if ($text -notmatch "(?m)\bclass\s+$([regex]::Escape($class.Name))\b") {
            $missing.Add("TEST_CLASS_DECLARATION_MISSING:$($class.Fqcn)")
        }
    }
    return $missing.ToArray()
}

function Initialize-MavenDependencies {
    Write-Host 'Preparing exact-test reactor dependencies without executing tests.'
    Push-Location $RepositoryRoot
    try {
        & $wrapper -s $settings -pl common -am -DskipTests install | Out-Host
        $prepareExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($prepareExit -ne 0) {
        throw "Maven dependency preparation failed with exit code $prepareExit"
    }
}

function Convert-SurefireXml {
    param([System.IO.FileInfo]$File)

    $document = [System.Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $true
    $document.Load($File.FullName)
    $suite = $document.DocumentElement
    if ($null -eq $suite -or $suite.LocalName -ne 'testsuite') {
        throw "Surefire XML has no testsuite root: $($File.FullName)"
    }

    $declared = [ordered]@{}
    foreach ($attribute in @('tests', 'failures', 'errors', 'skipped')) {
        $value = 0
        if (-not [int]::TryParse($suite.GetAttribute($attribute), [ref]$value) -or $value -lt 0) {
            throw "Surefire XML has invalid $attribute count: $($File.FullName)"
        }
        $declared[$attribute] = $value
    }
    $testCases = @($suite.SelectNodes('./testcase'))
    $actualFailures = @($testCases | Where-Object { $null -ne $_.SelectSingleNode('./failure') }).Count
    $actualErrors = @($testCases | Where-Object { $null -ne $_.SelectSingleNode('./error') }).Count
    $actualSkipped = @($testCases | Where-Object { $null -ne $_.SelectSingleNode('./skipped') }).Count
    if ($declared.tests -ne $testCases.Count -or
            $declared.failures -ne $actualFailures -or
            $declared.errors -ne $actualErrors -or
            $declared.skipped -ne $actualSkipped) {
        throw "Surefire XML declared counts do not match testcase nodes: $($File.FullName)"
    }

    return [pscustomobject]@{
        File = $File
        Name = $suite.GetAttribute('name')
        Tests = $declared.tests
        Failures = $declared.failures
        Errors = $declared.errors
        Skipped = $declared.skipped
        TestCases = $testCases
    }
}

function Invoke-ExactMavenTests {
    param(
        [string]$Name,
        [object[]]$Classes,
        [string]$EvidenceOutput
    )

    $missing = @(Get-MissingExactTestClasses -Classes $Classes)
    if ($missing.Count -gt 0) {
        throw "Exact Maven selection contains missing classes: $($missing -join ', ')"
    }
    $simpleNames = @($Classes | ForEach-Object { $_.Name })
    if (($simpleNames | Sort-Object -Unique).Count -ne $simpleNames.Count) {
        throw "$Name contains duplicate simple class names"
    }

    Remove-StaleSurefireXml
    $selection = $simpleNames -join ','
    Write-Host "Running $Name exact Maven selection: $selection"
    Push-Location $RepositoryRoot
    try {
        $arguments = @(
            '-s', $settings,
            '-pl', 'ai-service',
            "-Dtest=$selection"
        )
        if (-not [string]::IsNullOrWhiteSpace($EvidenceOutput)) {
            $arguments += "-Dmemory.v1.evidence.path=$EvidenceOutput"
        }
        $arguments += 'test'
        & $wrapper @arguments | Out-Host
        $mavenExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($mavenExit -ne 0) {
        throw "$Name Maven selection failed with exit code $mavenExit"
    }

    $reports = if (Test-Path -LiteralPath $surefireRoot -PathType Container) {
        @(Get-ChildItem -LiteralPath $surefireRoot -File -Filter 'TEST-*.xml' | Sort-Object Name)
    } else {
        @()
    }
    if ($reports.Count -eq 0) {
        throw "$Name produced no Surefire XML reports after the stale XML cleanup"
    }

    $suites = [ordered]@{}
    foreach ($report in $reports) {
        $parsed = Convert-SurefireXml -File $report
        if ([string]::IsNullOrWhiteSpace($parsed.Name)) {
            throw "Surefire XML has a blank suite name: $($report.FullName)"
        }
        if ($suites.Contains($parsed.Name)) {
            throw "Duplicate Surefire suite XML for $($parsed.Name)"
        }
        $suites[$parsed.Name] = $parsed
    }

    $expectedNames = @($Classes | ForEach-Object { $_.Fqcn })
    $missingSuites = @($expectedNames | Where-Object { -not $suites.Contains($_) })
    $unexpectedSuites = @($suites.Keys | Where-Object { $_ -notin $expectedNames })
    if ($missingSuites.Count -gt 0 -or $unexpectedSuites.Count -gt 0) {
        throw "$Name Surefire suite mismatch; missing=[$($missingSuites -join ', ')], unexpected=[$($unexpectedSuites -join ', ')]"
    }

    $counts = [ordered]@{ run = 0; failures = 0; errors = 0; skipped = 0; reports = $suites.Count }
    foreach ($suite in $suites.Values) {
        $counts.run += [int]$suite.Tests
        $counts.failures += [int]$suite.Failures
        $counts.errors += [int]$suite.Errors
        $counts.skipped += [int]$suite.Skipped
    }
    if ($counts.run -lt $Classes.Count) {
        throw "$Name discovered fewer testcases than exact classes"
    }
    return [pscustomobject]@{
        Counts = [pscustomobject]$counts
        Suites = $suites
    }
}

function Get-SuiteCounts {
    param(
        [System.Collections.IDictionary]$Suites,
        [string[]]$FullyQualifiedClasses
    )

    $counts = [ordered]@{ run = 0; failures = 0; errors = 0; skipped = 0; reports = 0 }
    foreach ($className in $FullyQualifiedClasses) {
        $suite = $Suites[$className]
        if ($null -eq $suite) {
            throw "Missing parsed Surefire suite: $className"
        }
        $counts.run += [int]$suite.Tests
        $counts.failures += [int]$suite.Failures
        $counts.errors += [int]$suite.Errors
        $counts.skipped += [int]$suite.Skipped
        $counts.reports++
    }
    return [pscustomobject]$counts
}

function Get-PrefixedTargetCounts {
    param(
        [object]$Suite,
        [string]$Prefix,
        [string[]]$ExpectedMethods
    )

    $matching = @($Suite.TestCases | Where-Object { $_.name -like "$Prefix*" })
    $names = @($matching | ForEach-Object { [string]$_.name })
    $missing = @($ExpectedMethods | Where-Object { $_ -notin $names })
    $unexpected = @($names | Where-Object { $_ -notin $ExpectedMethods })
    if ($missing.Count -gt 0 -or $unexpected.Count -gt 0) {
        throw "Named target mismatch for $Prefix; missing=[$($missing -join ', ')], unexpected=[$($unexpected -join ', ')]"
    }
    $failures = @($matching | Where-Object { $null -ne $_.SelectSingleNode('./failure') }).Count
    $errors = @($matching | Where-Object { $null -ne $_.SelectSingleNode('./error') }).Count
    $skipped = @($matching | Where-Object { $null -ne $_.SelectSingleNode('./skipped') }).Count
    return [pscustomobject][ordered]@{
        run = $matching.Count
        failures = $failures
        errors = $errors
        skipped = $skipped
        reports = 1
    }
}

function Invoke-ExactVitestGroup {
    param(
        [string]$Name,
        [string]$ConfigRelativePath,
        [string[]]$Files,
        [string]$OutputPath
    )

    $webRoot = Join-Path $RepositoryRoot 'web'
    $vitest = Join-Path $webRoot 'node_modules\.bin\vitest.cmd'
    $config = Join-Path $webRoot $ConfigRelativePath
    foreach ($relative in $Files) {
        if (-not (Test-Path -LiteralPath (Join-Path $webRoot $relative) -PathType Leaf)) {
            throw "$Name exact frontend test file is missing: $relative"
        }
    }
    if (-not (Test-Path -LiteralPath $vitest -PathType Leaf)) {
        throw 'M06 frontend dependencies are not installed; run npm.cmd ci in web before the gate'
    }
    if (-not (Test-Path -LiteralPath $config -PathType Leaf)) {
        throw "$Name Vitest config is missing: $config"
    }

    Remove-ExactGeneratedFile -Path $OutputPath
    Push-Location $webRoot
    try {
        & $vitest run --config $config @Files --reporter=json "--outputFile=$OutputPath" | Out-Host
        $vitestExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($vitestExit -ne 0) {
        throw "$Name exact frontend tests failed with exit code $vitestExit"
    }
    if (-not (Test-Path -LiteralPath $OutputPath -PathType Leaf)) {
        throw "$Name exact frontend tests did not produce JSON evidence"
    }
    $result = Get-Content -LiteralPath $OutputPath -Raw -Encoding utf8 | ConvertFrom-Json
    $reportedFiles = @($result.testResults | ForEach-Object {
        ([string]$_.name).Replace('\', '/')
    })
    foreach ($relative in $Files) {
        $normalized = $relative.Replace('\', '/')
        if (-not ($reportedFiles | Where-Object { $_.EndsWith($normalized, [System.StringComparison]::OrdinalIgnoreCase) })) {
            throw "$Name frontend JSON is missing exact test file: $relative"
        }
    }
    if ($reportedFiles.Count -ne $Files.Count) {
        throw "$Name frontend JSON contains unexpected test files: $($reportedFiles -join ', ')"
    }
    $counts = [pscustomobject][ordered]@{
        run = [int]$result.numTotalTests
        failures = [int]$result.numFailedTests
        errors = 0
        skipped = [int]$result.numPendingTests
        reports = [int]$result.numTotalTestSuites
    }
    if ($counts.run -lt 1 -or $counts.failures -ne 0 -or $counts.skipped -ne 0) {
        throw "$Name frontend counts are not passing: run=$($counts.run), failures=$($counts.failures), skipped=$($counts.skipped)"
    }
    return $counts
}

function Invoke-ExactFrontendTests {
    $groups = @(
        Invoke-ExactVitestGroup `
            -Name 'M06 contract' `
            -ConfigRelativePath 'vitest.memory-contract.config.js' `
            -Files @(
                'src/api/conversation.memory-contract.test.js',
                'src/composables/useConversationSession.memory-contract.test.js',
                'src/composables/useConversationWorkspace.memory-contract.test.js',
                'src/utils/jwt.memory-contract.test.js') `
            -OutputPath $frontendContractReportPath
        Invoke-ExactVitestGroup `
            -Name 'M06 flipped baseline' `
            -ConfigRelativePath 'vitest.config.js' `
            -Files @(
                'src/utils/useGlobalAi.memory-baseline.test.js',
                'src/views/components/GlobalAiAssistant.memory-baseline.test.js',
                'src/views/editor/EditorView.memory-baseline.test.js') `
            -OutputPath $frontendBaselineReportPath
        Invoke-ExactVitestGroup `
            -Name 'M06 independent UI acceptance' `
            -ConfigRelativePath 'vitest.memory-ui.config.js' `
            -Files @(
                'src/views/components/GlobalAiAssistant.memory-ui.test.js',
                'src/views/editor/EditorView.memory-ui.test.js') `
            -OutputPath $frontendUiReportPath
    )
    $counts = [ordered]@{ run = 0; failures = 0; errors = 0; skipped = 0; reports = 0 }
    foreach ($group in $groups) {
        foreach ($field in @('run', 'failures', 'errors', 'skipped', 'reports')) {
            $counts[$field] += [int]$group.$field
        }
    }

    $webRoot = Join-Path $RepositoryRoot 'web'
    Push-Location $webRoot
    try {
        & node --test 'src/api/asyncJob.test.mjs' | Out-Host
        $nodeExit = $LASTEXITCODE
        if ($nodeExit -eq 0) {
            & npm.cmd run build | Out-Host
            $buildExit = $LASTEXITCODE
        } else {
            $buildExit = 1
        }
    } finally {
        Pop-Location
    }
    if ($nodeExit -ne 0) {
        throw "M06 async job regression failed with exit code $nodeExit"
    }
    if ($buildExit -ne 0) {
        throw "M06 production build failed with exit code $buildExit"
    }
    return [pscustomobject]$counts
}

function Read-GateEvidence {
    if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) {
        throw "Memory integrated gate evidence is missing: $evidencePath"
    }
    $evidence = Get-Content -LiteralPath $evidencePath -Raw -Encoding utf8 | ConvertFrom-Json
    $requiredZero = @(
        'messageLoss',
        'duplicateSequence',
        'coverageGap',
        'coverageDuplicate',
        'staleSummaryOverwrite',
        'unsupportedConfirmedItems',
        'successfulCrossUserAccess',
        'tokenOverBudget'
    )
    foreach ($field in $requiredZero) {
        if ($evidence.PSObject.Properties.Name -notcontains $field -or [int]$evidence.$field -ne 0) {
            throw "Memory integrated evidence gate is not zero: $field"
        }
    }
    foreach ($field in @('conversations', 'messages', 'summaries', 'redisKeys', 'runtimeStateKeys')) {
        if ($evidence.deleteResidue.PSObject.Properties.Name -notcontains $field -or
                [int]$evidence.deleteResidue.$field -ne 0) {
            throw "Memory integrated deletion residue is not zero: $field"
        }
    }
    if ([int]$evidence.modelCallPathsCovered -ne 7 -or [int]$evidence.requiredModelCallPaths -ne 7) {
        throw 'Memory integrated evidence does not cover all seven model call paths'
    }
    return $evidence
}

function New-EmptyCounts {
    return [pscustomobject][ordered]@{ run = 0; failures = 0; errors = 0; skipped = 0; reports = 0 }
}

function Save-MemoryReport {
    param(
        [ValidateSet('ASSET_VERIFIED', 'PENDING_INTEGRATION', 'PENDING_EXTERNAL', 'AUTO_VERIFIED', 'FAILED')][string]$Status,
        [object]$AssetCounts,
        [object]$IntegratedCounts,
        [object]$Evidence,
        [string[]]$Unverified,
        [bool]$ManualPending
    )

    $asset = if ($null -eq $AssetCounts) { New-EmptyCounts } else { $AssetCounts }
    $integrated = if ($null -eq $IntegratedCounts) { New-EmptyCounts } else { $IntegratedCounts }
    $gateValue = {
        param([string]$Name)
        if ($null -eq $Evidence) { return $null }
        return $Evidence.$Name
    }
    $report = [ordered]@{
        schemaVersion = 1
        reportType = 'FINAL_ACCEPTANCE'
        stage = 'M07'
        status = $Status
        finalAcceptance = ($Status -eq 'AUTO_VERIFIED')
        manualStatus = if ($ManualPending) { 'MANUAL_PENDING' } else { 'NOT_READY' }
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
            java = (Get-ToolVersion -Command 'java' -Arguments @('-version'))
            node = (Get-ToolVersion -Command 'node' -Arguments @('--version'))
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
            run = [int]$asset.run + [int]$integrated.run
            failures = [int]$asset.failures + [int]$integrated.failures
            errors = [int]$asset.errors + [int]$integrated.errors
            skipped = [int]$asset.skipped + [int]$integrated.skipped
            asset = $AssetCounts
            integrated = $IntegratedCounts
        }
        gates = [ordered]@{
            messageLoss = (& $gateValue 'messageLoss')
            duplicateSequence = (& $gateValue 'duplicateSequence')
            coverageGap = (& $gateValue 'coverageGap')
            coverageDuplicate = (& $gateValue 'coverageDuplicate')
            staleSummaryOverwrite = (& $gateValue 'staleSummaryOverwrite')
            unsupportedConfirmedItems = (& $gateValue 'unsupportedConfirmedItems')
            successfulCrossUserAccess = (& $gateValue 'successfulCrossUserAccess')
            deleteResidue = (& $gateValue 'deleteResidue')
            tokenOverBudget = (& $gateValue 'tokenOverBudget')
            modelCallPathsCovered = (& $gateValue 'modelCallPathsCovered')
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
            tokenBudget = if ($null -eq $Evidence) { $null } else { $Evidence.tokenBudget }
            summaryCalls = if ($null -eq $Evidence) { $null } else { [int]$Evidence.summaryCalls }
            summaryFailures = if ($null -eq $Evidence) { $null } else { [int]$Evidence.summaryFailures }
        }
        stageGate = $null
        unverified = @($Unverified)
    }
    $report | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Host "Memory V1 machine-readable report: $reportPath"
}

$assetClasses = @(
    [pscustomobject]@{ Name = 'MemoryV1FixtureTest'; Fqcn = 'com.javaee.aiservice.memory.acceptance.MemoryV1FixtureTest' },
    [pscustomobject]@{ Name = 'MemoryV1SummarySchemaTest'; Fqcn = 'com.javaee.aiservice.memory.acceptance.MemoryV1SummarySchemaTest' },
    [pscustomobject]@{ Name = 'MemoryV1ReportSchemaTest'; Fqcn = 'com.javaee.aiservice.memory.acceptance.MemoryV1ReportSchemaTest' }
)
$integratedClasses = @(
    [pscustomobject]@{ Name = 'MemoryV1IntegratedGateTest'; Fqcn = 'com.javaee.aiservice.memory.acceptance.MemoryV1IntegratedGateTest' },
    [pscustomobject]@{ Name = 'MemoryV1ExpectedContractTest'; Fqcn = 'com.javaee.aiservice.memory.MemoryV1ExpectedContractTest' },
    [pscustomobject]@{ Name = 'JdbcConversationStoreTest'; Fqcn = 'com.javaee.aiservice.conversation.store.JdbcConversationStoreTest' },
    [pscustomobject]@{ Name = 'LegacyConversationImporterTest'; Fqcn = 'com.javaee.aiservice.conversation.service.LegacyConversationImporterTest' },
    [pscustomobject]@{ Name = 'ConversationManagerCharacterizationTest'; Fqcn = 'com.javaee.aiservice.conversation.ConversationManagerCharacterizationTest' },
    [pscustomobject]@{ Name = 'RuntimeStateServiceTest'; Fqcn = 'com.javaee.aiservice.conversation.runtime.RuntimeStateServiceTest' },
    [pscustomobject]@{ Name = 'LegacyRuntimeStateReaderTest'; Fqcn = 'com.javaee.aiservice.conversation.runtime.LegacyRuntimeStateReaderTest' },
    [pscustomobject]@{ Name = 'ContextManagerRuntimeCompatibilityTest'; Fqcn = 'com.javaee.aiservice.conversation.ContextManagerRuntimeCompatibilityTest' },
    [pscustomobject]@{ Name = 'MemoryContextBuilderTest'; Fqcn = 'com.javaee.aiservice.memory.context.MemoryContextBuilderTest' },
    [pscustomobject]@{ Name = 'AgentConversationMemoryCharacterizationTest'; Fqcn = 'com.javaee.aiservice.agent.execution.AgentConversationMemoryCharacterizationTest' },
    [pscustomobject]@{ Name = 'ConversationSummaryServiceTest'; Fqcn = 'com.javaee.aiservice.memory.summary.ConversationSummaryServiceTest' },
    [pscustomobject]@{ Name = 'BoundedSummaryModelClientTest'; Fqcn = 'com.javaee.aiservice.memory.summary.BoundedSummaryModelClientTest' },
    [pscustomobject]@{ Name = 'ConversationCursorCodecTest'; Fqcn = 'com.javaee.aiservice.conversation.api.ConversationCursorCodecTest' },
    [pscustomobject]@{ Name = 'ConversationApiServiceTest'; Fqcn = 'com.javaee.aiservice.conversation.api.ConversationApiServiceTest' },
    [pscustomobject]@{ Name = 'ConversationControllerTest'; Fqcn = 'com.javaee.aiservice.conversation.api.ConversationControllerTest' },
    [pscustomobject]@{ Name = 'AgentControllerConversationBaselineTest'; Fqcn = 'com.javaee.aiservice.controller.AgentControllerConversationBaselineTest' },
    [pscustomobject]@{ Name = 'AgentExecutionServiceTest'; Fqcn = 'com.javaee.aiservice.agent.execution.AgentExecutionServiceTest' },
    [pscustomobject]@{ Name = 'AgentControllerTest'; Fqcn = 'com.javaee.aiservice.controller.AgentControllerTest' }
)
$targetMethods = [ordered]@{
    m02 = @(
        'm02PersistsStructuredMessagesWithMonotonicSequence',
        'm02RetriesDoNotDuplicateUserOrAssistantMessages',
        'm02HistorySurvivesServiceRestartAndRedisExpiry',
        'm02ConversationAccessIsOwnerIsolated'
    )
    m03 = @(
        'm03RuntimeStateUsesWhitelistAndBoundedTtl',
        'm03RuntimeStateDoesNotLeakAcrossConversations',
        'm03ConversationDeletionCascadesRuntimeDerivatives',
        'm03ContinuationAndApprovalCancellationEnforceOwnershipInsideService'
    )
    m04 = @(
        'm04AllConversationModelCallsUseMemoryContextBuilder',
        'm04CurrentMessageIsNotDuplicatedAndRecentHistoryIsContinuous',
        'm04ToolCallAndResultAreNeverSplitByBudgeting',
        'm04OversizedCurrentMessageFailsWithStableError'
    )
    m05 = @(
        'm05SummaryCoverageIsContinuousAndDigestVerified',
        'm05SummaryPreservesSourceAndFactDecisionStatusRules',
        'm05ConcurrentSummaryUpdateRejectsStaleVersion',
        'm05SummaryFailureDoesNotAdvanceCoverage'
    )
}
$externalPending = @(
    'PENDING_EXTERNAL:REAL_BROWSER_JWT',
    'PENDING_EXTERNAL:REAL_MYSQL',
    'PENDING_EXTERNAL:REAL_REDIS',
    'PENDING_EXTERNAL:REAL_MODEL',
    'PENDING_EXTERNAL:REAL_QDRANT',
    'PENDING_EXTERNAL:REMOTE_EMBEDDING'
)

Write-Host "Memory V1 acceptance root: $RepositoryRoot"
Write-Host "Git branch/head: $branch / $head"
Write-Host 'Fixture boundary: deterministic H2/MySQL-mode and mocks; no external services.'

if ($Integrated) {
    $dirtyBefore = @(Get-DirtyWorktreeEntries)
    if ($dirtyBefore.Count -gt 0) {
        Save-MemoryReport `
            -Status 'FAILED' `
            -AssetCounts $null `
            -IntegratedCounts $null `
            -Evidence $null `
            -Unverified (@('DIRTY_WORKTREE') + $externalPending) `
            -ManualPending $true
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck `
            -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
        Write-Host "[FAILED] Integrated acceptance requires a clean worktree; dirty entries=$($dirtyBefore.Count)."
        exit 1
    }
}

Initialize-MavenDependencies
$assetRun = Invoke-ExactMavenTests -Name 'Memory V1 asset' -Classes $assetClasses -EvidenceOutput ''
if ($assetRun.Counts.failures -ne 0 -or $assetRun.Counts.errors -ne 0 -or $assetRun.Counts.skipped -ne 0) {
    throw 'Memory V1 asset tests are not all passing with skipped=0'
}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck -RepositoryRoot $RepositoryRoot
if ($LASTEXITCODE -ne 0) {
    throw "Memory V1 sensitive-data check failed with exit code $LASTEXITCODE"
}

if (-not $Integrated) {
    Save-MemoryReport `
        -Status 'ASSET_VERIFIED' `
        -AssetCounts $assetRun.Counts `
        -IntegratedCounts $null `
        -Evidence $null `
        -Unverified (@(
            'PENDING_INTEGRATION:M02_M06_NAMED_TESTS_NOT_RUN',
            'PENDING_INTEGRATION:M07_INTEGRATED_GATE_NOT_RUN'
        ) + $externalPending) `
        -ManualPending $false
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck `
        -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    if ($LASTEXITCODE -ne 0) {
        throw "Memory V1 report boundary check failed with exit code $LASTEXITCODE"
    }
    Write-Host '[ASSET_VERIFIED] Fixture, schemas, exact asset suites and sensitive-data boundary passed.'
    Write-Host '[PENDING_INTEGRATION] Production Memory V1 behavior was not evaluated.'
    exit 0
}

Remove-ExactGeneratedFile -Path $readinessPath
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $readinessCheck `
    -RepositoryRoot $RepositoryRoot -OutputPath $readinessPath
$readinessExit = $LASTEXITCODE
if ($readinessExit -notin @(0, 2)) {
    throw "Memory V1 readiness check failed with unexpected exit code $readinessExit"
}
if (-not (Test-Path -LiteralPath $readinessPath -PathType Leaf)) {
    throw 'Memory V1 readiness check did not produce JSON evidence'
}
$readiness = Get-Content -LiteralPath $readinessPath -Raw -Encoding utf8 | ConvertFrom-Json
$readinessMissing = @($readiness.missing)
$m06cMissing = @($readinessMissing | Where-Object { $_ -like 'M06C_*' })
$blockingReadiness = @($readinessMissing | Where-Object { $_ -notlike 'M06C_*' })
$missingExactClasses = @(Get-MissingExactTestClasses -Classes $integratedClasses)
if ($blockingReadiness.Count -gt 0 -or $missingExactClasses.Count -gt 0) {
    $reasons = @($blockingReadiness) + @($missingExactClasses)
    Save-MemoryReport `
        -Status 'PENDING_INTEGRATION' `
        -AssetCounts $assetRun.Counts `
        -IntegratedCounts $null `
        -Evidence $null `
        -Unverified (($reasons | ForEach-Object { "PENDING_INTEGRATION:$_" }) + $externalPending) `
        -ManualPending $true
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck `
        -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host "[PENDING_INTEGRATION] Blocking source/test evidence is missing: $($reasons -join ', ')"
    exit 2
}

try {
    Remove-ExactGeneratedFile -Path $evidencePath
    $javaRun = Invoke-ExactMavenTests `
        -Name 'Memory V1 M02-M06 and regression' `
        -Classes $integratedClasses `
        -EvidenceOutput $evidencePath
    if ($javaRun.Counts.failures -ne 0 -or $javaRun.Counts.errors -ne 0 -or $javaRun.Counts.skipped -ne 0) {
        throw 'Memory V1 exact Java aggregation is not all passing with skipped=0'
    }

    $expectedSuite = $javaRun.Suites['com.javaee.aiservice.memory.MemoryV1ExpectedContractTest']
    $targetCounts = [ordered]@{}
    foreach ($prefix in $targetMethods.Keys) {
        $targetCounts[$prefix.ToUpperInvariant()] = Get-PrefixedTargetCounts `
            -Suite $expectedSuite -Prefix $prefix -ExpectedMethods $targetMethods[$prefix]
    }
    $integratedSuite = $javaRun.Suites[
        'com.javaee.aiservice.memory.acceptance.MemoryV1IntegratedGateTest']
    $integratedCase = @($integratedSuite.TestCases | Where-Object {
        $_.name -eq 'deterministicConversationSummaryContextAndDeletionLifecycleProducesEvidence'
    })
    if ($integratedCase.Count -ne 1 -or $null -ne $integratedCase[0].SelectSingleNode('./skipped')) {
        throw 'The named MemoryV1IntegratedGateTest testcase did not execute exactly once'
    }

    $frontendCounts = Invoke-ExactFrontendTests
    $evidence = Read-GateEvidence
    $components = [ordered]@{
        M02Targets = $targetCounts.M02
        M02Specialists = Get-SuiteCounts -Suites $javaRun.Suites -FullyQualifiedClasses @(
            'com.javaee.aiservice.conversation.store.JdbcConversationStoreTest',
            'com.javaee.aiservice.conversation.service.LegacyConversationImporterTest',
            'com.javaee.aiservice.conversation.ConversationManagerCharacterizationTest')
        M03Targets = $targetCounts.M03
        M03Specialists = Get-SuiteCounts -Suites $javaRun.Suites -FullyQualifiedClasses @(
            'com.javaee.aiservice.conversation.runtime.RuntimeStateServiceTest',
            'com.javaee.aiservice.conversation.runtime.LegacyRuntimeStateReaderTest',
            'com.javaee.aiservice.conversation.ContextManagerRuntimeCompatibilityTest')
        M04Targets = $targetCounts.M04
        M04Specialists = Get-SuiteCounts -Suites $javaRun.Suites -FullyQualifiedClasses @(
            'com.javaee.aiservice.memory.context.MemoryContextBuilderTest',
            'com.javaee.aiservice.agent.execution.AgentConversationMemoryCharacterizationTest')
        M05Targets = $targetCounts.M05
        M05Specialists = Get-SuiteCounts -Suites $javaRun.Suites -FullyQualifiedClasses @(
            'com.javaee.aiservice.memory.summary.ConversationSummaryServiceTest',
            'com.javaee.aiservice.memory.summary.BoundedSummaryModelClientTest')
        M06Backend = Get-SuiteCounts -Suites $javaRun.Suites -FullyQualifiedClasses @(
            'com.javaee.aiservice.conversation.api.ConversationCursorCodecTest',
            'com.javaee.aiservice.conversation.api.ConversationApiServiceTest',
            'com.javaee.aiservice.conversation.api.ConversationControllerTest',
            'com.javaee.aiservice.controller.AgentControllerConversationBaselineTest')
        M06Frontend = $frontendCounts
        M07Integrated = Get-SuiteCounts -Suites $javaRun.Suites -FullyQualifiedClasses @(
            'com.javaee.aiservice.memory.acceptance.MemoryV1IntegratedGateTest')
        AgentControllerRegression = Get-SuiteCounts -Suites $javaRun.Suites -FullyQualifiedClasses @(
            'com.javaee.aiservice.agent.execution.AgentExecutionServiceTest',
            'com.javaee.aiservice.controller.AgentControllerTest')
    }
    $integratedCounts = [pscustomobject][ordered]@{
        run = [int]$javaRun.Counts.run + [int]$frontendCounts.run
        failures = [int]$javaRun.Counts.failures + [int]$frontendCounts.failures
        errors = [int]$javaRun.Counts.errors + [int]$frontendCounts.errors
        skipped = [int]$javaRun.Counts.skipped + [int]$frontendCounts.skipped
        reports = [int]$javaRun.Counts.reports + [int]$frontendCounts.reports
        components = $components
    }
    $dirtyAfter = @(Get-DirtyWorktreeEntries)
    if ($dirtyAfter.Count -gt 0) {
        throw "Acceptance execution dirtied the worktree: $($dirtyAfter -join ', ')"
    }
} catch {
    $failureMessage = $_.Exception.Message
    Save-MemoryReport `
        -Status 'FAILED' `
        -AssetCounts $assetRun.Counts `
        -IntegratedCounts $null `
        -Evidence $null `
        -Unverified (@('AUTOMATED_GATE_FAILED', $failureMessage) + $externalPending) `
        -ManualPending $true
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck `
        -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
    Write-Host "[FAILED] $failureMessage"
    exit 1
}

$status = if ($m06cMissing.Count -gt 0) { 'PENDING_INTEGRATION' } else { 'AUTO_VERIFIED' }
$integrationPending = @($m06cMissing | ForEach-Object { "PENDING_INTEGRATION:$_" })
Save-MemoryReport `
    -Status $status `
    -AssetCounts $assetRun.Counts `
    -IntegratedCounts $integratedCounts `
    -Evidence $evidence `
    -Unverified ($integrationPending + $externalPending) `
    -ManualPending $true
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $sensitiveCheck `
    -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
if ($LASTEXITCODE -ne 0) {
    throw "Memory V1 final report boundary check failed with exit code $LASTEXITCODE"
}

Write-Host "[PASS] Exact deterministic suites passed: run=$($integratedCounts.run), failures=0, errors=0, skipped=0."
if ($status -eq 'PENDING_INTEGRATION') {
    Write-Host "[PENDING_INTEGRATION] M06-C UI dependencies are not integrated: $($m06cMissing -join ', ')"
    Write-Host '[MANUAL_PENDING] Real browser/JWT, MySQL, Redis, model, Qdrant and remote Embedding remain unverified.'
    exit 2
} else {
    Write-Host '[AUTO_VERIFIED] All deterministic Memory V1 gates passed with failures/errors/skipped equal to zero.'
    Write-Host '[MANUAL_PENDING] Real browser/JWT, MySQL, Redis and model verification remain for user acceptance.'
    exit 0
}
