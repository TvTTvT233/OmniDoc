param([Parameter(Mandatory)][string]$ProofPath)
$ErrorActionPreference='Stop'
python3 (Join-Path $PSScriptRoot 'linux_runtime.py') restart --proof $ProofPath
exit $LASTEXITCODE
