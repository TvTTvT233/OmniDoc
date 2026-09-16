[CmdletBinding()]
param(
    [switch]$Integrated,
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..\..'
}
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$wrapper = Join-Path $RepositoryRoot 'mvnw.cmd'
$settings = Join-Path $RepositoryRoot '.mvn\settings-local.xml'
$leakCheck = Join-Path $PSScriptRoot 'assert-no-secret-leaks.ps1'

if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw "Maven wrapper not found: $wrapper"
}
if (-not (Test-Path -LiteralPath $settings -PathType Leaf)) {
    throw "Maven settings not found: $settings"
}

$branch = (& git -C $RepositoryRoot branch --show-current).Trim()
$head = (& git -C $RepositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read Git state'
}
Write-Host "T04 acceptance root: $RepositoryRoot"
Write-Host "Git branch/head: $branch / $head"

$testSelection = 'MockMcpServerTest,AgentExecutionServiceTest,AgentControllerTest,McpContractTest'
if ($Integrated) {
    $productionMcpRoot = Join-Path $RepositoryRoot 'ai-service\src\main\java\com\javaee\aiservice\agent\execution\mcp'
    $integratedSources = @(Get-ChildItem -LiteralPath $productionMcpRoot -Recurse -File -Filter '*.java' |
        Where-Object { $_.FullName -notmatch '[\\/]contract[\\/]' })
    if ($integratedSources.Count -eq 0) {
        throw 'Integrated mode requested, but no T01-T03 MCP production implementation is present. Status remains PENDING_INTEGRATION.'
    }
    $testSelection = '*Mcp*Test,AgentExecutionServiceTest,AgentControllerTest'
}

Write-Host "Running Maven acceptance selection: $testSelection"
& $wrapper -s $settings -pl ai-service -am "-Dtest=$testSelection" '-Dsurefire.failIfNoSpecifiedTests=false' test
if ($LASTEXITCODE -ne 0) {
    throw "Maven acceptance selection failed with exit code $LASTEXITCODE"
}

& $leakCheck -RepositoryRoot $RepositoryRoot
if ($LASTEXITCODE -ne 0) {
    throw "Secret leakage check failed with exit code $LASTEXITCODE"
}

if ($Integrated) {
    Write-Host '[PASS] Integrated MCP test selection and static leakage check completed.'
    Write-Host '[REVIEW_REQUIRED] Reconcile every row in 05-AUTO_TEST_REPORT.md before setting AUTO_VERIFIED.'
} else {
    Write-Host '[PASS] Baseline-compatible T04 fixture, contract, Agent/Controller regression, and static leakage checks completed.'
    Write-Host '[PENDING_INTEGRATION] T01 transport, T02 catalog/schema, and T03 routing/result assertions require the integrated implementation.'
}
