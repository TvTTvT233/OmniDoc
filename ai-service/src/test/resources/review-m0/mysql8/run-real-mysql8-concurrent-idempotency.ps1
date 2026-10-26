param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..\..\..')).Path
$containerName = 'review_m0_a_fix1_mysql8_' + $PID
$volumeName = 'review_m0_a_fix1_mysql8_data_' + $PID
$databaseName = 'review_m0_a_fix1_acceptance'
$runtimePassword = [Guid]::NewGuid().ToString('N')
$env:MAVEN_OPTS = '-Xms16m -Xmx256m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -Xshare:off'

function Wait-ForMysql {
    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        & docker exec --env "MYSQL_PWD=$runtimePassword" $containerName `
            mysqladmin ping --user=root --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        throw 'Dedicated MySQL 8 container did not become ready'
    }
}

function Invoke-Phase([string]$phase) {
    $env:REVIEW_M0_MYSQL_PHASE = $phase
    & .\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 `
        '-Dtest=ReviewM0RealMysql8ConcurrentIdempotencyIT' test
    if ($LASTEXITCODE -ne 0) {
        throw "Real MySQL concurrent idempotency phase failed"
    }
}

function Set-ConnectionEnvironment {
    $portLine = (& docker port $containerName '3306/tcp').Trim()
    $mysqlPort = [int]($portLine -replace '^.*:', '')
    $env:REVIEW_M0_MYSQL_JDBC_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/$($databaseName)?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:REVIEW_M0_MYSQL_USER = 'root'
    $env:REVIEW_M0_MYSQL_PASSWORD = $runtimePassword
}

try {
    & docker volume create $volumeName | Out-Null
    & docker run --detach --name $containerName --memory 512m `
        --publish '127.0.0.1::3306' `
        --env "MYSQL_ROOT_PASSWORD=$runtimePassword" `
        --env "MYSQL_DATABASE=$databaseName" `
        --mount "type=volume,source=$volumeName,target=/var/lib/mysql" `
        mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci `
        --innodb-buffer-pool-size=64M --performance-schema=OFF | Out-Null

    Wait-ForMysql
    Set-ConnectionEnvironment

    Push-Location $repositoryRoot
    try {
        Invoke-Phase 'seed'
        & docker restart $containerName | Out-Null
        Wait-ForMysql
        Set-ConnectionEnvironment
        Invoke-Phase 'verify'
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:\REVIEW_M0_MYSQL_PHASE -ErrorAction SilentlyContinue
    Remove-Item Env:\REVIEW_M0_MYSQL_JDBC_URL -ErrorAction SilentlyContinue
    Remove-Item Env:\REVIEW_M0_MYSQL_USER -ErrorAction SilentlyContinue
    Remove-Item Env:\REVIEW_M0_MYSQL_PASSWORD -ErrorAction SilentlyContinue
    $cleanupPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    & docker rm --force $containerName 2>$null | Out-Null
    & docker volume rm $volumeName 2>$null | Out-Null
    $ErrorActionPreference = $cleanupPreference
}
