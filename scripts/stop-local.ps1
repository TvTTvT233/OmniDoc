[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pidFile = Join-Path $repoRoot '.local\pids.json'

if (Test-Path -LiteralPath $pidFile) {
    $savedProcesses = Get-Content -LiteralPath $pidFile -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($property in $savedProcesses.PSObject.Properties) {
        $process = Get-Process -Id ([int]$property.Value) -ErrorAction SilentlyContinue
        if ($process) {
            & taskkill.exe /PID $process.Id /T /F *> $null
            if ($LASTEXITCODE -ne 0) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
            Write-Host "[stopped] $($property.Name)"
        }
    }
    Remove-Item -LiteralPath $pidFile -Force
}

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCommand) {
    Set-Location $repoRoot
    & $dockerCommand.Source compose -f docker-compose.local.yml stop
}
