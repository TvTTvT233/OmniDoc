[CmdletBinding()]
param()

$services = [ordered]@{
    'Gateway'          = 8080
    'User service'     = 8081
    'File service'     = 8082
    'AI service'       = 8083
    'Document service' = 8084
    'Web'              = 5173
    'MySQL'            = 3306
    'Redis'            = 6379
    'RabbitMQ'         = 5672
    'MinIO'            = 9000
}

$listeningPorts = [System.Collections.Generic.HashSet[int]]::new()
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { [void]$listeningPorts.Add([int]$_.LocalPort) }

$rows = foreach ($entry in $services.GetEnumerator()) {
    $isOpen = $listeningPorts.Contains([int]$entry.Value)
    [PSCustomObject]@{
        Component = $entry.Key
        Port = $entry.Value
        Status = if ($isOpen) { 'running' } else { 'stopped' }
    }
}

$rows | Format-Table -AutoSize
