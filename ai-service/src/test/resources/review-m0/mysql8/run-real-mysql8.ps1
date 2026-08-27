param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..\..\..')).Path
$containerName = 'review_m0_f1_mysql8_' + $PID
$databaseName = 'review_m0_f1_acceptance'
$runtimePassword = [Guid]::NewGuid().ToString('N')

try {
    & docker run --detach --name $containerName --memory 768m `
        --publish '127.0.0.1::3306' `
        --env "MYSQL_ROOT_PASSWORD=$runtimePassword" `
        --env "MYSQL_DATABASE=$databaseName" `
        --tmpfs '/var/lib/mysql:rw,noexec,nosuid,size=512m' `
        mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci | Out-Null

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

    $portLine = (& docker port $containerName '3306/tcp').Trim()
    $mysqlPort = [int]($portLine -replace '^.*:', '')
    $env:REVIEW_M0_MYSQL_JDBC_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/$($databaseName)?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:REVIEW_M0_MYSQL_USER = 'root'
    $env:REVIEW_M0_MYSQL_PASSWORD = $runtimePassword

    Push-Location $repositoryRoot
    try {
        & .\mvnw.cmd -s .mvn\settings-local.xml -pl ai-service -DforkCount=1 `
            '-Dtest=ReviewM0RealMysql8AcceptanceIT' test
        if ($LASTEXITCODE -ne 0) {
            throw "Real MySQL 8 acceptance failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:\REVIEW_M0_MYSQL_JDBC_URL -ErrorAction SilentlyContinue
    Remove-Item Env:\REVIEW_M0_MYSQL_USER -ErrorAction SilentlyContinue
    Remove-Item Env:\REVIEW_M0_MYSQL_PASSWORD -ErrorAction SilentlyContinue
    $cleanupPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    & docker rm --force $containerName 2>$null | Out-Null
    $ErrorActionPreference = $cleanupPreference
}
