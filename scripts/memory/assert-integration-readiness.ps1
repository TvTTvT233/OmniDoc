[CmdletBinding()]
param(
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..\..'
}
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path

$missing = [System.Collections.Generic.List[string]]::new()
$sourceGroups = @(
    [pscustomobject]@{ Id = 'M02_CONVERSATION_MODEL'; Path = 'ai-service\src\main\java\com\javaee\aiservice\conversation\model'; Token = 'Conversation' },
    [pscustomobject]@{ Id = 'M02_CONVERSATION_STORE'; Path = 'ai-service\src\main\java\com\javaee\aiservice\conversation\store'; Token = 'Conversation' },
    [pscustomobject]@{ Id = 'M03_RUNTIME_STATE'; Path = 'ai-service\src\main\java\com\javaee\aiservice\conversation\runtime'; Token = 'Runtime' },
    [pscustomobject]@{ Id = 'M04_MEMORY_CONTEXT'; Path = 'ai-service\src\main\java\com\javaee\aiservice\memory\context'; Token = 'MemoryContextBuilder' },
    [pscustomobject]@{ Id = 'M05_STRUCTURED_SUMMARY'; Path = 'ai-service\src\main\java\com\javaee\aiservice\memory\summary'; Token = 'Summary' }
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

$controllerRoot = Join-Path $RepositoryRoot 'ai-service\src\main\java\com\javaee\aiservice\controller'
$conversationApiFound = $false
if (Test-Path -LiteralPath $controllerRoot -PathType Container) {
    foreach ($source in Get-ChildItem -LiteralPath $controllerRoot -Recurse -File -Filter '*.java') {
        if ((Get-Content -LiteralPath $source.FullName -Raw -Encoding utf8) -match '/api/ai/conversations') {
            $conversationApiFound = $true
            break
        }
    }
}
if (-not $conversationApiFound) {
    $missing.Add('M06_STRUCTURED_CONVERSATION_API')
}

$integratedGate = Join-Path $RepositoryRoot 'ai-service\src\test\java\com\javaee\aiservice\memory\acceptance\MemoryV1IntegratedGateTest.java'
if (-not (Test-Path -LiteralPath $integratedGate -PathType Leaf)) {
    $missing.Add('M07_INTEGRATED_GATE')
}

if ($missing.Count -gt 0) {
    Write-Host "[PENDING_INTEGRATION] Missing required Memory V1 production/integration evidence: $($missing -join ', ')"
    exit 2
}

Write-Host '[PASS] Memory V1 production source groups and M07 integrated gate are present. Runtime P0/P1 gates must still pass.'
