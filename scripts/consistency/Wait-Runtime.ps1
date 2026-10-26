[CmdletBinding()]
param([int[]]$Ports=@(8080,8081,8082,8083,8084),[int]$TimeoutSeconds=90)
$ErrorActionPreference='Stop'
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $pending=@()
    foreach($port in $Ports){
        $client=[Net.Sockets.TcpClient]::new()
        try {$connected=$client.ConnectAsync('127.0.0.1',$port).Wait(200) -and $client.Connected}
        catch {$connected=$false}
        finally {$client.Dispose()}
        if(-not $connected){$pending+=$port}
    }
    if(-not $pending.Count){Write-Output "All requested local ports listening: $($Ports -join ','); functional HTTP checks remain separate.";exit 0}
    Start-Sleep -Milliseconds 500
}while([DateTime]::UtcNow -lt $deadline)
throw "Local runtime ports not ready: $($pending -join ',')"
