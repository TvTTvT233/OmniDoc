Set-StrictMode -Version Latest

function Get-MemoryToolVersion {
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

function Assert-MemoryPowerShellSyntax {
    param([string[]]$Paths)

    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Memory stage-gate asset is missing: $path"
        }
        $tokens = $null
        $parseErrors = $null
        [void][System.Management.Automation.Language.Parser]::ParseFile(
            $path,
            [ref]$tokens,
            [ref]$parseErrors
        )
        if (@($parseErrors).Count -gt 0) {
            $locations = @($parseErrors | ForEach-Object { "$($_.Extent.StartLineNumber):$($_.ErrorId)" })
            throw "PowerShell syntax check failed for $path ($($locations -join ', '))"
        }
    }
}

function Invoke-MemoryMavenSelection {
    param(
        [string]$RepositoryRoot,
        [string]$Name,
        [string]$Selection,
        [string]$ReportNamePattern
    )

    $wrapper = Join-Path $RepositoryRoot 'mvnw.cmd'
    $settings = Join-Path $RepositoryRoot '.mvn\settings-local.xml'
    foreach ($required in @($wrapper, $settings)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "Maven acceptance asset is missing: $required"
        }
    }

    $started = (Get-Date).AddSeconds(-2)
    Write-Host "Running $Name Maven selection: $Selection"
    & $wrapper -s $settings -pl ai-service -am "-Dtest=$Selection" '-Dsurefire.failIfNoSpecifiedTests=false' test | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "$Name Maven selection failed with exit code $LASTEXITCODE"
    }

    $surefireRoot = Join-Path $RepositoryRoot 'ai-service\target\surefire-reports'
    $reports = @()
    if (Test-Path -LiteralPath $surefireRoot -PathType Container) {
        $reports = @(Get-ChildItem -LiteralPath $surefireRoot -File -Filter 'TEST-*.xml' |
            Where-Object { $_.Name -like $ReportNamePattern -and $_.LastWriteTime -ge $started })
    }
    if ($reports.Count -eq 0) {
        throw "$Name produced no fresh Surefire XML reports"
    }

    $counts = [ordered]@{ run = 0; failures = 0; errors = 0; skipped = 0; reports = $reports.Count }
    foreach ($report in $reports) {
        [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw -Encoding utf8
        $suite = $xml.testsuite
        $counts.run += [int]$suite.tests
        $counts.failures += [int]$suite.failures
        $counts.errors += [int]$suite.errors
        $counts.skipped += [int]$suite.skipped
    }

    return [pscustomobject]@{
        Counts = [pscustomobject]$counts
        Reports = $reports
    }
}

function Merge-MemoryTestCounts {
    param([object[]]$Components)

    $result = [ordered]@{
        run = 0
        failures = 0
        errors = 0
        skipped = 0
        reports = 0
        components = [ordered]@{}
    }
    foreach ($component in $Components) {
        $counts = $component.Counts
        $result.run += [int]$counts.run
        $result.failures += [int]$counts.failures
        $result.errors += [int]$counts.errors
        $result.skipped += [int]$counts.skipped
        $result.reports += [int]$counts.reports
        $result.components[$component.Name] = $counts
    }
    return [pscustomobject]$result
}

function Write-MemoryStageReport {
    param(
        [string]$RepositoryRoot,
        [string]$ReportPath,
        [ValidateSet('M02', 'M03_A', 'M03', 'M06_A')][string]$Stage,
        [ValidateSet('ASSET_VERIFIED', 'PENDING_INTEGRATION', 'STAGE_VERIFIED', 'FAILED')][string]$Status,
        [object]$AssetCounts,
        [object]$IntegratedCounts,
        [bool]$Ready,
        [string[]]$RequiredTargets,
        [string[]]$ReadinessMissing,
        [int]$TargetTestsExpected,
        [Nullable[int]]$TargetTestsSkipped,
        [string[]]$RegressionGroups,
        [string[]]$Unverified
    )

    $fixturePath = Join-Path $RepositoryRoot 'ai-service\src\test\resources\memory\memory-v1-fixture.json'
    if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
        throw "Memory V1 fixture is missing: $fixturePath"
    }
    $fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding utf8 | ConvertFrom-Json
    $branch = (& git -C $RepositoryRoot branch --show-current).Trim()
    $head = (& git -C $RepositoryRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read Git state for stage report'
    }

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
        reportType = 'STAGE_GATE'
        stage = $Stage
        status = $Status
        finalAcceptance = $false
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
            java = (Get-MemoryToolVersion -Command 'java' -Arguments @('-version'))
            node = (Get-MemoryToolVersion -Command 'node' -Arguments @('--version'))
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
        stageGate = [ordered]@{
            ready = $Ready
            requiredTargets = @($RequiredTargets)
            readinessMissing = @($ReadinessMissing)
            targetTestsExpected = $TargetTestsExpected
            targetTestsSkipped = $TargetTestsSkipped
            regressionGroups = @($RegressionGroups)
        }
        unverified = @($Unverified)
    }

    $parent = Split-Path -Parent $ReportPath
    [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $ReportPath -Encoding utf8
    Write-Host "Memory V1 $Stage stage report: $ReportPath"
}
