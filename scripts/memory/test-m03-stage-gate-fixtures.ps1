[CmdletBinding()]
param([string]$FixtureRoot)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

if ([string]::IsNullOrWhiteSpace($FixtureRoot)) {
    $FixtureRoot = Join-Path $PSScriptRoot 'fixtures\m03'
}
$FixtureRoot = (Resolve-Path -LiteralPath $FixtureRoot).Path
. (Join-Path $PSScriptRoot 'm03-stage-gate-checks.ps1')

$fixtures = @(Get-ChildItem -LiteralPath $FixtureRoot -File -Filter '*.json' | Sort-Object Name)
if ($fixtures.Count -lt 4) {
    throw "M03 stage-gate self-test requires at least four fixtures: $FixtureRoot"
}

foreach ($fixtureFile in $fixtures) {
    $fixture = Get-Content -LiteralPath $fixtureFile.FullName -Raw -Encoding utf8 | ConvertFrom-Json
    $result = Test-MemoryM03ProductionReadiness `
        -RuntimeText (@($fixture.sources.runtime) -join "`n") `
        -ConversationText (@($fixture.sources.conversation) -join "`n") `
        -AgentText (@($fixture.sources.agent) -join "`n")

    $actualStatus = if ($result.Ready) { 'STAGE_VERIFIED' } else { 'PENDING_INTEGRATION' }
    if ($result.Ready -ne [bool]$fixture.expectedReady) {
        throw "Fixture $($fixture.id) ready mismatch: expected=$($fixture.expectedReady), actual=$($result.Ready), missing=$($result.Missing -join ',')"
    }
    if ($actualStatus -ne [string]$fixture.expectedStatus) {
        throw "Fixture $($fixture.id) status mismatch: expected=$($fixture.expectedStatus), actual=$actualStatus"
    }

    $expectedMissing = @($fixture.expectedMissing | ForEach-Object { [string]$_ } | Sort-Object)
    $actualMissing = @($result.Missing | Sort-Object)
    if (($expectedMissing -join '|') -ne ($actualMissing -join '|')) {
        throw "Fixture $($fixture.id) missing mismatch: expected=$($expectedMissing -join ','), actual=$($actualMissing -join ',')"
    }
    Write-Host "[PASS] M03 gate fixture $($fixture.id): $actualStatus"
}

Write-Host "[PASS] M03 stage-gate fixture self-test passed $($fixtures.Count)/$($fixtures.Count)."
