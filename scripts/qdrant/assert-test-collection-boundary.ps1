[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$CollectionPrefix = $env:QV04_TEST_COLLECTION_PREFIX,
    [string[]]$CollectionNames = @($env:QV04_TEST_COLLECTION, $env:QV04_TEST_COLLECTION_ALIAS)
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..\..'
}
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path

if ([string]::IsNullOrWhiteSpace($CollectionPrefix)) {
    $CollectionPrefix = 'qv04_test_'
}
$CollectionPrefix = $CollectionPrefix.ToLowerInvariant()

if (-not $CollectionPrefix.StartsWith('qv04_test_')) {
    throw "Collection prefix must start with qv04_test_: $CollectionPrefix"
}
if ($CollectionPrefix -notmatch '^[a-z0-9_-]+$') {
    throw "Collection prefix contains unsafe characters: $CollectionPrefix"
}

$forbidden = @('omnidoc_chunks_active', 'omnidoc_chunks', 'production', 'prod')
$checked = 0
foreach ($name in $CollectionNames) {
    if ([string]::IsNullOrWhiteSpace($name)) {
        continue
    }
    $normalized = $name.ToLowerInvariant()
    if ($forbidden -contains $normalized) {
        throw "Refusing production/stable Collection name: $name"
    }
    if (-not $normalized.StartsWith($CollectionPrefix)) {
        throw "Collection name is outside QV04 test prefix '$CollectionPrefix': $name"
    }
    if ($normalized -notmatch '^[a-z0-9_-]+$') {
        throw "Collection name contains unsafe characters: $name"
    }
    $checked++
}

$integratedTest = Join-Path $RepositoryRoot 'ai-service\src\test\java\com\javaee\aiservice\rag\vector\acceptance\Qv04IntegratedGateTest.java'
if (-not (Test-Path -LiteralPath $integratedTest -PathType Leaf)) {
    throw "Integrated Collection boundary test is missing: $integratedTest"
}
$integratedText = Get-Content -LiteralPath $integratedTest -Raw -Encoding utf8
if ($integratedText -notmatch 'Qv04CollectionBoundary\.requireTestName') {
    throw 'Integrated gate does not enforce Qv04CollectionBoundary before backend operations.'
}

Write-Host "[PASS] QV04 Collection boundary requires '$CollectionPrefix'; validated $checked concrete test name(s)."
