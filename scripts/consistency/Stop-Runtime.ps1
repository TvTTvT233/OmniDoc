[CmdletBinding()]
param([string[]]$Services)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$manifest=Join-Path $repo '.local/consistency-evidence/runtime-processes.json'
if(-not(Test-Path -LiteralPath $manifest)){Write-Output 'No consistency processes recorded.';exit 0}
$latest=@(Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json)|Group-Object service|ForEach-Object {$_.Group|Sort-Object startedAtUtc -Descending|Select-Object -First 1}
foreach($entry in $latest){
    if($Services -and $entry.service -notin $Services){continue}
    $process=Get-Process -Id $entry.processId -ErrorAction SilentlyContinue
    if(-not $process){continue}
    if($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$entry.startedAtUtc).ToUniversalTime().Ticks){throw "Process identity mismatch pid=$($entry.processId); refusing stop"}
    Stop-Process -Id $process.Id
    Write-Output "Stopped verified consistency process $($entry.service) pid=$($entry.processId)."
}
