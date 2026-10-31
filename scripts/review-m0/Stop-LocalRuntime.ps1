[CmdletBinding()]
param([string[]]$Services)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$manifest=Join-Path $repo '.local/review-m0-20261027/runtime-processes.json'
if(-not(Test-Path $manifest)){return}
$latest=@(Get-Content $manifest -Raw|ConvertFrom-Json)|Group-Object service|ForEach-Object {$_.Group|Sort-Object startedAtUtc -Descending|Select-Object -First 1}
foreach($entry in $latest){
    if($Services -and $entry.service -notin $Services){continue}
    $process=Get-Process -Id $entry.processId -ErrorAction SilentlyContinue
    if(-not $process){continue}
    if($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$entry.startedAtUtc).ToUniversalTime().Ticks){throw 'Process identity mismatch; refusing stop'}
    Stop-Process -Id $process.Id
    if(-not $process.WaitForExit(10000)){throw "Verified $($entry.service) did not exit within the bounded wait"}
    Write-Output "Stopped verified $($entry.service)."
}
