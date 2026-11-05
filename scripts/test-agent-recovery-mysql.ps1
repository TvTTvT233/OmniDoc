$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$container = 'omnidoc_agent_recovery_it_' + $PID
$volume = $container + '_data'
$password = [Guid]::NewGuid().ToString('N')
$evidence = Join-Path $repo '.local/recovery-evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$env:RECOVERY_MYSQL_PASSWORD = $password
$env:RECOVERY_MYSQL_FIXTURE = Join-Path $evidence 'mysql-fixture.private.json'
function Wait-MySql {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        & docker exec --env "MYSQL_PWD=$password" $container mysqladmin ping --user=root --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Seconds 1
    }
    throw 'Dedicated MySQL did not start'
}
try {
    & docker volume create $volume | Out-Null
    & docker run --detach --name $container --memory 512m --publish '127.0.0.1::3306' --env "MYSQL_ROOT_PASSWORD=$password" --env 'MYSQL_DATABASE=agent_recovery_acceptance' --mount "type=volume,source=$volume,target=/var/lib/mysql" mysql:8.0 --innodb-buffer-pool-size=64M --performance-schema=OFF | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated MySQL creation failed' }
    Wait-MySql
    $port = [int]((& docker port $container '3306/tcp').Trim() -replace '^.*:', '')
    $env:RECOVERY_MYSQL_URL = "jdbc:mysql://127.0.0.1:$port/agent_recovery_acceptance?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    Push-Location $repo
    try {
        foreach ($phase in @('seed','verify')) {
            $port = [int]((& docker port $container '3306/tcp').Trim() -replace '^.*:', '')
            $env:RECOVERY_MYSQL_URL = "jdbc:mysql://127.0.0.1:$port/agent_recovery_acceptance?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
            $env:RECOVERY_MYSQL_PHASE = $phase
            & ./mvnw.cmd -s .mvn/settings-local.xml -pl ai-service -am '-Dtest=AgentRecoveryMysqlIT' '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $evidence "mysql-$phase.log")
            if ($LASTEXITCODE -ne 0) { throw "MySQL $phase gate failed" }
            if ($phase -eq 'seed') { & docker restart $container | Out-Null; Wait-MySql }
        }
        @{ seed='passed'; restart='passed'; verify='passed'; isolated=$true } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'mysql-result.json')
    } finally { Pop-Location }
} finally {
    & docker rm --force $container 2>$null | Out-Null
    & docker volume rm $volume 2>$null | Out-Null
    foreach ($name in @('RECOVERY_MYSQL_PASSWORD','RECOVERY_MYSQL_FIXTURE','RECOVERY_MYSQL_URL','RECOVERY_MYSQL_PHASE')) { [Environment]::SetEnvironmentVariable($name,$null,'Process') }
}
