[CmdletBinding()]
param([Parameter(Mandatory)][string]$ProofPath)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Runtime.ps1') -Action RestartAi -ProofPath $ProofPath
