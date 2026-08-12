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
    $ReportDirectory = Join-Path $RepositoryRoot 'ai-service\target\review-m0-acceptance'
}
$ReportDirectory = [System.IO.Path]::GetFullPath($ReportDirectory)
[System.IO.Directory]::CreateDirectory($ReportDirectory) | Out-Null

$wrapper = Join-Path $RepositoryRoot 'mvnw.cmd'
$settings = Join-Path $RepositoryRoot '.mvn\settings-local.xml'
$resourceRoot = Join-Path $RepositoryRoot 'ai-service\src\test\resources\review-m0\acceptance'
$fixturePath = Join-Path $resourceRoot 'review-m0-p0-fixtures.json'
$selectorPath = Join-Path $resourceRoot 'review-m0-integration-selector.json'
$reportPath = Join-Path $ReportDirectory 'review-m0-acceptance-report.json'
$evidencePath = Join-Path $ReportDirectory 'review-m0-integrated-evidence.json'
$sensitiveCheck = Join-Path $PSScriptRoot 'assert-no-sensitive-data.ps1'
$surefireRoot = Join-Path $RepositoryRoot 'ai-service\target\surefire-reports'
$assetClasses = @(
    'com.javaee.aiservice.review.m0.acceptance.ReviewM0FixtureContractTest',
    'com.javaee.aiservice.review.m0.acceptance.ReviewM0ReportSchemaTest',
    'com.javaee.aiservice.review.m0.acceptance.ReviewM0IntegrationSelectorContractTest',
    'com.javaee.aiservice.review.m0.acceptance.ReviewM0StaticGateContractTest'
)
$requiredTargetIds = @(1..15 | ForEach-Object { 'M0-P0-{0:D3}' -f $_ })
$requiredP0Names = @(
    'missingBeforeUploadConfirmation',
    'deterministicFindingsWithoutRuleEvidence',
    'deterministicFindingsWithoutMaterialEvidence',
    'systemErrorsReportedAsMissing',
    'cannotConfirmReportedAsMissing',
    'originalFileMutations',
    'objectKeyMutations',
    'remoteSendsWithoutMatchingConsent',
    'candidateChecklistsUsedForReview',
    'nestedArchivesAccepted',
    'crossUserSuccessfulReads',
    'duplicatePublishedReports',
    'staleReportsMarkedCurrent',
    'readyReportsWithIgnoredFindings',
    'incrementalFullMismatches'
)
$startedAt = (Get-Date).ToUniversalTime().ToString('o')

foreach ($requiredFile in @($wrapper, $settings, $fixturePath, $selectorPath, $sensitiveCheck)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Review M0 acceptance asset is missing: $requiredFile"
    }
}

$fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding utf8 | ConvertFrom-Json
$selector = Get-Content -LiteralPath $selectorPath -Raw -Encoding utf8 | ConvertFrom-Json
$branch = (& git -C $RepositoryRoot branch --show-current).Trim()
$head = (& git -C $RepositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[a-f0-9]{40}$') {
    throw 'Unable to read a valid Git branch and HEAD'
}

function Get-ToolVersion {
    param([string]$Command, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Command @Arguments 2>&1)
        if ($LASTEXITCODE -ne 0 -or $output.Count -eq 0) { return 'NOT_AVAILABLE' }
        return ([string]$output[0]).Trim()
    } catch {
        return 'NOT_AVAILABLE'
    } finally {
        $ErrorActionPreference = $previous
    }
}

function New-Counts {
    return [pscustomobject][ordered]@{ run = 0; failures = 0; errors = 0; skipped = 0; reports = 0 }
}

function Add-Counts {
    param([object]$Left, [object]$Right)
    return [pscustomobject][ordered]@{
        run = [int]$Left.run + [int]$Right.run
        failures = [int]$Left.failures + [int]$Right.failures
        errors = [int]$Left.errors + [int]$Right.errors
        skipped = [int]$Left.skipped + [int]$Right.skipped
        reports = [int]$Left.reports + [int]$Right.reports
    }
}

function Assert-ExactProperties {
    param(
        [object]$Object,
        [string[]]$Expected,
        [string]$Context
    )
    if ($null -eq $Object) { throw "$Context is null" }
    $actual = @($Object.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    $expectedSorted = @($Expected | Sort-Object)
    $missing = @($expectedSorted | Where-Object { $_ -notin $actual })
    $extra = @($actual | Where-Object { $_ -notin $expectedSorted })
    if ($missing.Count -gt 0 -or $extra.Count -gt 0) {
        throw "$Context properties differ from the contract; missing=[$($missing -join ', ')], extra=[$($extra -join ', ')]"
    }
}

function Remove-StaleSurefireXml {
    $expected = [System.IO.Path]::GetFullPath(
        (Join-Path $RepositoryRoot 'ai-service\target\surefire-reports'))
    $actual = [System.IO.Path]::GetFullPath($surefireRoot)
    if (-not $actual.Equals($expected, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected Surefire path: $actual"
    }
    if (Test-Path -LiteralPath $surefireRoot -PathType Container) {
        Get-ChildItem -LiteralPath $surefireRoot -File -Filter 'TEST-*.xml' |
            ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    }
}

function Get-TestSourcePath {
    param([string]$FullyQualifiedClass)
    $relative = $FullyQualifiedClass.Replace('.', [System.IO.Path]::DirectorySeparatorChar) + '.java'
    return Join-Path $RepositoryRoot (Join-Path 'ai-service\src\test\java' $relative)
}

function Assert-ExactTestClassesExist {
    param([string[]]$Classes)
    foreach ($className in $Classes) {
        $source = Get-TestSourcePath -FullyQualifiedClass $className
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Exact Review M0 test class is missing: $className"
        }
        $simple = $className.Substring($className.LastIndexOf('.') + 1)
        $text = Get-Content -LiteralPath $source -Raw -Encoding utf8
        if ($text -notmatch "(?m)\bclass\s+$([regex]::Escape($simple))\b") {
            throw "Exact Review M0 test declaration is missing: $className"
        }
    }
}

function Initialize-MavenDependencies {
    Push-Location $RepositoryRoot
    try {
        & $wrapper -s $settings -pl common -am -DskipTests install | Out-Host
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) {
        throw "Maven dependency preparation failed with exit code $exitCode"
    }
}

function Read-SurefireSuites {
    $suites = [ordered]@{}
    $reports = if (Test-Path -LiteralPath $surefireRoot -PathType Container) {
        @(Get-ChildItem -LiteralPath $surefireRoot -File -Filter 'TEST-*.xml' | Sort-Object Name)
    } else { @() }
    if ($reports.Count -eq 0) { throw 'Exact Review M0 test selection produced no Surefire XML' }

    foreach ($file in $reports) {
        $document = [System.Xml.XmlDocument]::new()
        $document.Load($file.FullName)
        $suite = $document.DocumentElement
        if ($null -eq $suite -or $suite.LocalName -ne 'testsuite') {
            throw "Invalid Surefire XML root: $($file.FullName)"
        }
        $counts = [ordered]@{}
        foreach ($field in @('tests', 'failures', 'errors', 'skipped')) {
            $value = 0
            if (-not [int]::TryParse($suite.GetAttribute($field), [ref]$value) -or $value -lt 0) {
                throw "Invalid Surefire $field count: $($file.FullName)"
            }
            $counts[$field] = $value
        }
        $cases = @($suite.SelectNodes('./testcase'))
        if ($counts.tests -ne $cases.Count -or
                $counts.failures -ne @($cases | Where-Object { $null -ne $_.SelectSingleNode('./failure') }).Count -or
                $counts.errors -ne @($cases | Where-Object { $null -ne $_.SelectSingleNode('./error') }).Count -or
                $counts.skipped -ne @($cases | Where-Object { $null -ne $_.SelectSingleNode('./skipped') }).Count) {
            throw "Surefire declared counts differ from testcase nodes: $($file.FullName)"
        }
        $name = $suite.GetAttribute('name')
        if ([string]::IsNullOrWhiteSpace($name) -or $suites.Contains($name)) {
            throw "Missing or duplicate Surefire suite name: $name"
        }
        $suites[$name] = [pscustomobject]@{
            Counts = [pscustomobject][ordered]@{
                run = $counts.tests
                failures = $counts.failures
                errors = $counts.errors
                skipped = $counts.skipped
                reports = 1
            }
            TestCases = $cases
        }
    }
    return $suites
}

function Invoke-ExactMavenTests {
    param([string]$Name, [string[]]$Classes, [string]$EvidenceOutput)
    Assert-ExactTestClassesExist -Classes $Classes
    Remove-StaleSurefireXml
    $simpleNames = @($Classes | ForEach-Object { $_.Substring($_.LastIndexOf('.') + 1) })
    if (($simpleNames | Sort-Object -Unique).Count -ne $simpleNames.Count) {
        throw "$Name contains duplicate simple class names"
    }
    $arguments = @('-s', $settings, '-pl', 'ai-service', "-Dtest=$($simpleNames -join ',')")
    if (-not [string]::IsNullOrWhiteSpace($EvidenceOutput)) {
        $absoluteEvidence = [System.IO.Path]::GetFullPath($EvidenceOutput)
        if (Test-Path -LiteralPath $absoluteEvidence -PathType Leaf) {
            Remove-Item -LiteralPath $absoluteEvidence -Force
        }
        $arguments += "-Dreview.m0.acceptance.evidence.path=$absoluteEvidence"
    }
    $arguments += 'test'
    Write-Host "Running $Name exact Maven selection: $($simpleNames -join ',')"
    Push-Location $RepositoryRoot
    try {
        & $wrapper @arguments | Out-Host
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) { throw "$Name failed with exit code $exitCode" }
    $suites = Read-SurefireSuites
    $missing = @($Classes | Where-Object { -not $suites.Contains($_) })
    $unexpected = @($suites.Keys | Where-Object { $_ -notin $Classes })
    if ($missing.Count -gt 0 -or $unexpected.Count -gt 0) {
        throw "$Name suite mismatch; missing=[$($missing -join ', ')], unexpected=[$($unexpected -join ', ')]"
    }
    $counts = New-Counts
    foreach ($className in $Classes) { $counts = Add-Counts -Left $counts -Right $suites[$className].Counts }
    if ($counts.run -lt $Classes.Count -or $counts.failures -ne 0 -or $counts.errors -ne 0 -or $counts.skipped -ne 0) {
        throw "$Name is not fully passing: run=$($counts.run), failures=$($counts.failures), errors=$($counts.errors), skipped=$($counts.skipped)"
    }
    return [pscustomobject]@{ Counts = $counts; Suites = $suites }
}

function Assert-SelectorReady {
    Assert-ExactProperties -Object $selector -Expected @(
        'schemaVersion', 'contractVersion', 'state', 'adapterId', 'testClasses',
        'targetSelectors', 'staticScanRoots', 'evidenceSystemProperty', 'pendingReasons') `
        -Context 'Integration selector'
    if ($selector.schemaVersion -ne 1 -or $selector.contractVersion -ne 'REVIEW_M0_CONTRACT_V1' -or
            $selector.evidenceSystemProperty -ne 'review.m0.acceptance.evidence.path') {
        throw 'Integration selector identity does not match the frozen Review M0 contract'
    }
    if ($selector.state -ne 'READY') {
        return @([string[]]$selector.pendingReasons)
    }
    $missing = [System.Collections.Generic.List[string]]::new()
    if ([string]::IsNullOrWhiteSpace([string]$selector.adapterId)) { $missing.Add('ADAPTER_ID_MISSING') }
    $classes = @($selector.testClasses)
    if ($classes.Count -eq 0) { $missing.Add('EXACT_TEST_CLASSES_MISSING') }
    $targetProperties = @($selector.targetSelectors.PSObject.Properties)
    foreach ($targetProperty in $targetProperties) {
        Assert-ExactProperties -Object $targetProperty.Value -Expected @('className', 'methodName') `
            -Context "Integration selector target $($targetProperty.Name)"
    }
    $actualTargets = @($targetProperties | ForEach-Object { $_.Name } | Sort-Object)
    $targetDifference = @($requiredTargetIds | Where-Object { $_ -notin $actualTargets }) +
        @($actualTargets | Where-Object { $_ -notin $requiredTargetIds })
    if ($targetDifference.Count -gt 0 -or $actualTargets.Count -ne 15) { $missing.Add('EXACT_TARGET_MAP_INVALID') }
    if (@($selector.staticScanRoots).Count -eq 0) { $missing.Add('STATIC_SCAN_ROOTS_MISSING') }
    return $missing.ToArray()
}

function Assert-TargetSelectionExecuted {
    param([System.Collections.IDictionary]$Suites)
    foreach ($targetId in $requiredTargetIds) {
        $mapping = $selector.targetSelectors.PSObject.Properties[$targetId].Value
        $suite = $Suites[[string]$mapping.className]
        if ($null -eq $suite) { throw "Target $targetId suite was not selected: $($mapping.className)" }
        $matches = @($suite.TestCases | Where-Object { $_.name -eq [string]$mapping.methodName })
        if ($matches.Count -ne 1) { throw "Target $targetId exact method did not execute once: $($mapping.methodName)" }
        if ($null -ne $matches[0].SelectSingleNode('./failure') -or
                $null -ne $matches[0].SelectSingleNode('./error') -or
                $null -ne $matches[0].SelectSingleNode('./skipped')) {
            throw "Target $targetId did not pass: $($mapping.methodName)"
        }
    }
}

function Read-IntegratedEvidence {
    if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) {
        throw "Integrated evidence is missing: $evidencePath"
    }
    $evidence = Get-Content -LiteralPath $evidencePath -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-ExactProperties -Object $evidence -Expected @(
        'schemaVersion', 'contractVersion', 'adapterId', 'targetResults', 'p0') `
        -Context 'Integrated evidence'
    if ($evidence.schemaVersion -ne 1 -or $evidence.contractVersion -ne 'REVIEW_M0_CONTRACT_V1' -or
            $evidence.adapterId -ne $selector.adapterId) {
        throw 'Integrated evidence identity does not match the selector contract'
    }
    $actualTargets = @($evidence.targetResults.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    if ($actualTargets.Count -ne 15 -or @($requiredTargetIds | Where-Object { $_ -notin $actualTargets }).Count -ne 0) {
        throw 'Integrated evidence does not contain exactly the 15 required P0 targets'
    }
    foreach ($targetId in $requiredTargetIds) {
        $target = $evidence.targetResults.PSObject.Properties[$targetId].Value
        Assert-ExactProperties -Object $target -Expected @('status', 'assertions') `
            -Context "Integrated evidence target $targetId"
        if ($target.status -ne 'PASS' -or [int]$target.assertions -lt 1) {
            throw "Integrated target evidence is not passing: $targetId"
        }
    }
    Assert-ExactProperties -Object $evidence.p0 -Expected $requiredP0Names -Context 'Integrated evidence P0 counts'
    foreach ($name in $requiredP0Names) {
        if ($evidence.p0.PSObject.Properties.Name -notcontains $name -or [int]$evidence.p0.$name -ne 0) {
            throw "Integrated P0 count is absent or non-zero: $name"
        }
    }
    return $evidence
}

function New-NullP0 {
    $p0 = [ordered]@{}
    foreach ($name in $requiredP0Names) { $p0[$name] = $null }
    return $p0
}

function Save-Report {
    param(
        [ValidateSet('ASSET_VERIFIED', 'PENDING_INTEGRATION', 'AUTO_VERIFIED')][string]$Status,
        [ValidateSet('ASSET', 'INTEGRATED')][string]$Mode,
        [object]$AssetCounts,
        [object]$IntegratedCounts,
        [object]$Evidence,
        [string[]]$Missing,
        [string[]]$Unverified
    )
    $asset = if ($null -eq $AssetCounts) { New-Counts } else { $AssetCounts }
    $integrated = if ($null -eq $IntegratedCounts) { $null } else { $IntegratedCounts }
    $total = if ($null -eq $integrated) { $asset } else { Add-Counts -Left $asset -Right $integrated }
    $report = [ordered]@{
        schemaVersion = 'REVIEW_M0_ACCEPTANCE_REPORT_V1'
        contractVersion = 'REVIEW_M0_CONTRACT_V1'
        gateType = 'FINAL_ACCEPTANCE'
        mode = $Mode
        status = $Status
        manualStatus = if ($Status -eq 'AUTO_VERIFIED') { 'MANUAL_PENDING' } else { 'NOT_READY' }
        finalAcceptance = ($Status -eq 'AUTO_VERIFIED')
        baselineCommit = $head
        startedAt = $startedAt
        finishedAt = (Get-Date).ToUniversalTime().ToString('o')
        counts = [ordered]@{
            tests = $total.run
            passed = $total.run - $total.failures - $total.errors - $total.skipped
            failed = $total.failures
            errors = $total.errors
            skipped = $total.skipped
        }
        p0 = if ($null -eq $Evidence) { New-NullP0 } else { $Evidence.p0 }
        external = [ordered]@{
            realMysql = 'NOT_RUN'
            realFileService = 'NOT_RUN'
            realDocumentService = 'NOT_RUN'
            realRemoteModel = 'NOT_RUN'
            realBrowser = 'NOT_RUN'
        }
        readinessMissing = @($Missing)
        unverified = @($Unverified)
    }
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding utf8
}

$assetResult = $null
$integratedResult = $null
try {
    Initialize-MavenDependencies
    $assetResult = Invoke-ExactMavenTests -Name 'Review M0 asset contracts' -Classes $assetClasses -EvidenceOutput ''

    if (-not $Integrated) {
        Save-Report -Status 'ASSET_VERIFIED' -Mode 'ASSET' `
            -AssetCounts $assetResult.Counts -IntegratedCounts $null `
            -Evidence $null -Missing @([string[]]$selector.pendingReasons) `
            -Unverified @('REVIEW_M0_PRODUCTION_BEHAVIOR_NOT_EXECUTED', 'REAL_FILES_AND_EXTERNAL_SERVICES_NOT_EXECUTED')
        & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
        if ($LASTEXITCODE -ne 0) { throw "Sensitive-data gate failed with exit code $LASTEXITCODE" }
        Write-Host "[ASSET_VERIFIED] Review M0 acceptance assets passed. Production behavior was not executed. Report: $reportPath"
        exit 0
    }

    $missing = @(Assert-SelectorReady)
    if ($missing.Count -gt 0) {
        Save-Report -Status 'PENDING_INTEGRATION' -Mode 'INTEGRATED' `
            -AssetCounts $assetResult.Counts -IntegratedCounts $null `
            -Evidence $null -Missing $missing `
            -Unverified @('REVIEW_M0_PRODUCTION_BEHAVIOR_NOT_EXECUTED', 'INTEGRATION_ADAPTER_NOT_READY')
        & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath
        if ($LASTEXITCODE -ne 0) { throw "Sensitive-data gate failed with exit code $LASTEXITCODE" }
        Write-Host "[PENDING_INTEGRATION] Review M0 production adapter is not ready; Integrated mode fails closed. Report: $reportPath"
        exit 2
    }

    $integratedClasses = @($selector.testClasses | ForEach-Object { [string]$_ })
    $integratedResult = Invoke-ExactMavenTests -Name 'Review M0 integrated adapter' `
        -Classes $integratedClasses -EvidenceOutput $evidencePath
    Assert-TargetSelectionExecuted -Suites $integratedResult.Suites
    $evidence = Read-IntegratedEvidence
    Save-Report -Status 'AUTO_VERIFIED' -Mode 'INTEGRATED' -AssetCounts $assetResult.Counts `
        -IntegratedCounts $integratedResult.Counts -Evidence $evidence -Missing @() -Unverified @()
    & $sensitiveCheck -RepositoryRoot $RepositoryRoot -ReportPath $reportPath `
        -StaticScanRoots @($selector.staticScanRoots)
    if ($LASTEXITCODE -ne 0) { throw "Sensitive-data gate failed with exit code $LASTEXITCODE" }
    Write-Host "[AUTO_VERIFIED] Review M0 exact integrated targets and aggregate gates passed. Report: $reportPath"
    exit 0
} catch {
    $message = $_.Exception.Message
    if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
        Remove-Item -LiteralPath $reportPath -Force
    }
    [Console]::Error.WriteLine("[FAILED] Review M0 acceptance gate: $message")
    [Console]::Error.WriteLine('No acceptance report was retained for this failed execution.')
    exit 1
}
