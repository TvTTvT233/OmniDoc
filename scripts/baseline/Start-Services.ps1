[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JavaPath,
    [ValidateSet('user-service','file-service','document-service','ai-service','gateway-service')]
    [string[]]$Services = @('user-service','file-service','document-service','gateway-service'),
    [string]$ProviderEnvFile,
    [switch]$NoExternalModels
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runtime = Join-Path $repo '.local/baseline-runtime'
$logs = Join-Path $runtime 'logs'
New-Item -ItemType Directory -Path $logs -Force | Out-Null
function Import-Environment([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Missing configuration: $Path" }
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z_0-9]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
}
Import-Environment (Join-Path $runtime 'runtime.env')
if ($ProviderEnvFile) { Import-Environment $ProviderEnvFile }
if (-not (Test-Path -LiteralPath $JavaPath)) { throw 'JavaPath must point to a local JDK 17 java.exe.' }
$version = (& $JavaPath -version 2>&1 | Out-String)
if ($version -notmatch 'version "17\.') { throw 'JDK 17 is required.' }
$ports = @{ 'user-service'=8081; 'file-service'=8082; 'document-service'=8084; 'ai-service'=8083; 'gateway-service'=8080 }
$manifestPath = Join-Path $runtime 'processes.json'
$manifest = @()
if (Test-Path $manifestPath) { $manifest = @(Get-Content $manifestPath -Raw | ConvertFrom-Json) }
foreach ($service in $Services) {
    $port = $ports[$service]
    $client = [Net.Sockets.TcpClient]::new()
    try { $occupied = $client.ConnectAsync('127.0.0.1',$port).Wait(400) -and $client.Connected } catch { $occupied = $false } finally { $client.Dispose() }
    if ($occupied) { throw "Port $port is already occupied; no existing process will be reused or stopped." }
    if ($service -eq 'ai-service' -and -not $NoExternalModels -and (-not $env:DASHSCOPE_API_KEY -or -not $env:OPENAI_API_KEY)) {
        throw 'AI model configuration is missing. Supply ProviderEnvFile; no fake success or implicit key is created.'
    }
    $jar = Join-Path $repo "$service/target/$service-1.0.0.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "Build artifact missing: $jar" }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $out = Join-Path $logs "$service-$stamp.out.log"
    $err = Join-Path $logs "$service-$stamp.err.log"
    $arguments = @('-Xms128m','-Xmx512m','-jar',('"' + $jar + '"'),'--spring.profiles.active=local','--server.address=127.0.0.1','--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl','--logging.level.com.javaee=INFO')
    if ($service -eq 'ai-service' -and $NoExternalModels) {
        # Empty SDK keys fail before remote requests; all chat models and summary are disabled.
        # Remove inherited SDK key sources too. This profile must not be accepted as a model/RAG pass.
        [Environment]::SetEnvironmentVariable('DASHSCOPE_API_KEY', '', 'Process')
        [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', '', 'Process')
        $arguments += '--spring.config.additional-location=' + (Join-Path $PSScriptRoot 'no-external-models.yml').Replace('\','/')
    }
    $process = Start-Process -FilePath $JavaPath -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    $manifest += [pscustomobject]@{ service=$service; processId=$process.Id; startedAtUtc=$process.StartTime.ToUniversalTime().ToString('o'); jar=$jar; port=$port; stdout=$out; stderr=$err }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content $manifestPath -Encoding utf8
    Write-Output "Launched $service pid=$($process.Id); startup and HTTP behavior still require verification."
}
