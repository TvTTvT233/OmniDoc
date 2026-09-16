[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$ProviderClass = $env:QV04_ACCEPTANCE_PROVIDER_CLASS
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..\..'
}
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path

$missing = [System.Collections.Generic.List[string]]::new()
$sourceGroups = @(
    [pscustomobject]@{ Id = 'QV01_LOCAL_BACKEND'; Path = 'ai-service\src\main\java\com\javaee\aiservice\rag\vector\local'; Token = 'LocalRedisHnswVectorBackend' },
    [pscustomobject]@{ Id = 'QV02_QDRANT_BACKEND'; Path = 'ai-service\src\main\java\com\javaee\aiservice\rag\vector\qdrant'; Token = 'QdrantVectorBackend' },
    [pscustomobject]@{ Id = 'QV03_ROUTING'; Path = 'ai-service\src\main\java\com\javaee\aiservice\rag\vector\routing'; Token = 'VectorBackendRouter' },
    [pscustomobject]@{ Id = 'QV03_MIGRATION'; Path = 'ai-service\src\main\java\com\javaee\aiservice\rag\vector\migration'; Token = 'VectorRecordExporter' }
)

foreach ($group in $sourceGroups) {
    $absolute = Join-Path $RepositoryRoot $group.Path
    $sources = @()
    if (Test-Path -LiteralPath $absolute -PathType Container) {
        $sources = @(Get-ChildItem -LiteralPath $absolute -Recurse -File -Filter '*.java')
    }
    if ($sources.Count -eq 0) {
        $missing.Add($group.Id)
        continue
    }
    $found = $false
    foreach ($source in $sources) {
        if ((Get-Content -LiteralPath $source.FullName -Raw -Encoding utf8) -match [regex]::Escape($group.Token)) {
            $found = $true
            break
        }
    }
    if (-not $found) {
        $missing.Add($group.Id)
    }
}

if ([string]::IsNullOrWhiteSpace($ProviderClass)) {
    $missing.Add('QV04_INTEGRATED_PROVIDER_CLASS')
} else {
    $providerRelative = 'ai-service\src\test\java\' + ($ProviderClass.Replace('.', '\')) + '.java'
    $providerPath = Join-Path $RepositoryRoot $providerRelative
    if (-not (Test-Path -LiteralPath $providerPath -PathType Leaf)) {
        $missing.Add('QV04_INTEGRATED_PROVIDER_SOURCE')
    } else {
        $providerText = Get-Content -LiteralPath $providerPath -Raw -Encoding utf8
        if ($providerText -notmatch 'Qv04IntegratedAcceptanceProvider') {
            $missing.Add('QV04_INTEGRATED_PROVIDER_INTERFACE')
        }
    }
}

if ($missing.Count -gt 0) {
    Write-Host "[PENDING_INTEGRATION] Missing required QV01-QV03 integration evidence: $($missing -join ', ')"
    exit 2
}

Write-Host '[PASS] QV01-QV03 source groups and the QV04 integrated provider are present. Runtime gates still must pass.'
