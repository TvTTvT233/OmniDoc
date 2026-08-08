[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repoRoot

function Import-DotEnv {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing $Path. Copy .env.example to .env and fill in local values."
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Test-LocalPort {
    param([int]$Port)
    return Test-NetConnection -ComputerName localhost -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
}

function Wait-LocalPort {
    param(
        [string]$Name,
        [int]$Port,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-LocalPort -Port $Port) {
            Write-Host "[ready] $Name on port $Port"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not open port $Port within $TimeoutSeconds seconds."
}

Import-DotEnv -Path (Join-Path $repoRoot '.env')

$env:MYSQL_LOCAL_HOST = $env:MYSQL_HOST
$env:MYSQL_LOCAL_PORT = $env:MYSQL_PORT
$env:MYSQL_LOCAL_USERNAME = $env:MYSQL_USERNAME
$env:MYSQL_LOCAL_PASSWORD = $env:MYSQL_PASSWORD
$env:REDIS_LOCAL_HOST = $env:REDIS_HOST
$env:REDIS_LOCAL_PASSWORD = $env:REDIS_PASSWORD
$env:MINIO_LOCAL_ENDPOINT = $env:MINIO_ENDPOINT
$env:SPRING_PROFILES_ACTIVE = 'local'

$javaPath = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    (Get-Command java -ErrorAction SilentlyContinue).Source
}
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue

if (-not $javaPath) {
    throw 'Java is not on PATH. Install JDK 17 and open a new PowerShell window.'
}
if (-not $dockerCommand) {
    throw 'Docker is not on PATH. Install Docker Desktop and open a new PowerShell window.'
}
if (-not $SkipFrontend -and -not $npmCommand) {
    throw 'npm is not on PATH. Install Node.js LTS and open a new PowerShell window.'
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersionOutput = @(& $javaPath -version 2>&1)
$javaVersionExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
if ($javaVersionExitCode -ne 0) {
    throw "Failed to run Java at $javaPath."
}
$javaVersion = $javaVersionOutput | Select-Object -First 1
if ($javaVersion -notmatch '"17\.') {
    throw "OmniDoc requires JDK 17, but the active runtime is: $javaVersion"
}

& $dockerCommand.Source info *> $null
if ($LASTEXITCODE -ne 0) {
    $desktopPath = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (Test-Path -LiteralPath $desktopPath) {
        Start-Process -FilePath $desktopPath -WindowStyle Hidden
        Write-Host 'Waiting for Docker Desktop...'
        $deadline = (Get-Date).AddSeconds(120)
        do {
            Start-Sleep -Seconds 3
            & $dockerCommand.Source info *> $null
            if ($LASTEXITCODE -eq 0) {
                break
            }
        } while ((Get-Date) -lt $deadline)
    }
}
if ($LASTEXITCODE -ne 0) {
    throw 'Docker engine is unavailable. Restart Windows after enabling WSL2, then start Docker Desktop.'
}

Write-Host 'Starting MySQL, Redis, RabbitMQ, and MinIO...'
& $dockerCommand.Source compose -f docker-compose.local.yml up -d
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to start local infrastructure.'
}

Wait-LocalPort -Name 'MySQL' -Port 3306 -TimeoutSeconds 120
Wait-LocalPort -Name 'Redis' -Port 6379 -TimeoutSeconds 60
Wait-LocalPort -Name 'RabbitMQ' -Port 5672 -TimeoutSeconds 90
Wait-LocalPort -Name 'MinIO' -Port 9000 -TimeoutSeconds 90

if (-not $SkipBuild) {
    Write-Host 'Building backend modules...'
    $env:MAVEN_USER_HOME = Join-Path $repoRoot '.maven-cache'
    & (Join-Path $repoRoot 'mvnw.cmd') `
        '-ntp' `
        '-s' (Join-Path $repoRoot '.mvn\settings-local.xml') `
        "-Dmaven.repo.local=.maven-cache\repository" `
        '-DskipTests' `
        package
    if ($LASTEXITCODE -ne 0) {
        throw 'Backend build failed.'
    }
}

$runtimeDir = Join-Path $repoRoot '.local'
$logDir = Join-Path $runtimeDir 'logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$processIds = [ordered]@{}

function Start-JavaService {
    param(
        [string]$Name,
        [string]$Jar,
        [int]$Port
    )

    if (Test-LocalPort -Port $Port) {
        Write-Host "[skip] $Name is already listening on port $Port"
        return
    }

    if (-not (Test-Path -LiteralPath $Jar)) {
        throw "Missing artifact: $Jar. Run without -SkipBuild first."
    }

    $stdout = Join-Path $logDir "$Name.out.log"
    $stderr = Join-Path $logDir "$Name.err.log"
    $process = Start-Process `
        -FilePath $javaPath `
        -ArgumentList @('-jar', $Jar, '--spring.profiles.active=local') `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
    $processIds[$Name] = $process.Id

    try {
        Wait-LocalPort -Name $Name -Port $Port -TimeoutSeconds 120
    }
    catch {
        Write-Host "Last output from ${Name}:"
        Get-Content -LiteralPath $stdout -Tail 30 -ErrorAction SilentlyContinue
        Get-Content -LiteralPath $stderr -Tail 30 -ErrorAction SilentlyContinue
        throw
    }
}

Start-JavaService -Name 'user-service' -Jar (Join-Path $repoRoot 'user-service\target\user-service-1.0.0.jar') -Port 8081
Start-JavaService -Name 'file-service' -Jar (Join-Path $repoRoot 'file-service\target\file-service-1.0.0.jar') -Port 8082
Start-JavaService -Name 'document-service' -Jar (Join-Path $repoRoot 'document-service\target\document-service-1.0.0.jar') -Port 8084
Start-JavaService -Name 'ai-service' -Jar (Join-Path $repoRoot 'ai-service\target\ai-service-1.0.0.jar') -Port 8083
Start-JavaService -Name 'gateway-service' -Jar (Join-Path $repoRoot 'gateway-service\target\gateway-service-1.0.0.jar') -Port 8080

if (-not $SkipFrontend) {
    $webDir = Join-Path $repoRoot 'web'
    if (-not (Test-Path -LiteralPath (Join-Path $webDir 'node_modules'))) {
        Write-Host 'Installing frontend dependencies...'
        Push-Location $webDir
        try {
            & $npmCommand.Source install
            if ($LASTEXITCODE -ne 0) {
                throw 'Frontend dependency installation failed.'
            }
        }
        finally {
            Pop-Location
        }
    }

    if (-not (Test-LocalPort -Port 5173)) {
        $frontendProcess = Start-Process `
            -FilePath $npmCommand.Source `
            -ArgumentList @('run', 'dev', '--', '--host', '0.0.0.0') `
            -WorkingDirectory $webDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logDir 'web.out.log') `
            -RedirectStandardError (Join-Path $logDir 'web.err.log') `
            -PassThru
        $processIds['web'] = $frontendProcess.Id
        Wait-LocalPort -Name 'web' -Port 5173 -TimeoutSeconds 60
    }
}

$processIds | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeDir 'pids.json') -Encoding utf8

Write-Host ''
Write-Host 'OmniDoc local environment is running:'
Write-Host '  Web:       http://localhost:5173'
Write-Host '  Gateway:   http://localhost:8080'
Write-Host '  MinIO UI:  http://localhost:9001'
Write-Host '  RabbitMQ:  http://localhost:15672'
Write-Host ''
Write-Host 'AI calls require real DASHSCOPE_API_KEY / OPENAI_API_KEY values in .env.'
