[CmdletBinding()]
param([string[]]$Services)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$manifest = Join-Path $repo '.local/baseline-runtime/processes.json'
if (-not (Test-Path $manifest)) { Write-Output 'No recorded processes.'; exit 0 }
foreach ($entry in @(Get-Content $manifest -Raw | ConvertFrom-Json)) {
    if ($Services -and $entry.service -notin $Services) { continue }
    $process = Get-Process -Id $entry.processId -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    if ($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$entry.startedAtUtc).ToUniversalTime().Ticks) {
        throw "Process identity changed for pid=$($entry.processId); refusing to stop it."
    }
    Stop-Process -Id $process.Id
    Write-Output "Stopped recorded $($entry.service) pid=$($entry.processId)."
}
