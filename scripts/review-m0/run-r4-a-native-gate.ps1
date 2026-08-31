param(
    [ValidateSet('Preflight', 'Formal')]
    [string]$Mode = 'Preflight',
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
Add-Type -AssemblyName System.Net.Http

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$evidence = [System.IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))
$prefix = if ($Mode -eq 'Preflight') { 'r4ar4_preflight' } else { 'r4ar4_formal' }
$mysqlName = "${prefix}_mysql"
$volumeName = "${prefix}_data"
$databaseName = "review_m0_$prefix"
$databasePassword = [Guid]::NewGuid().ToString('N')
$jwtSecret = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$processes = New-Object System.Collections.Generic.List[System.Diagnostics.Process]
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$handler.Proxy = $null
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)
$assertions = 0
$ports = New-Object System.Collections.Generic.List[int]

function Assert-Equal {
    param($Expected, $Actual, [string]$Name)
    $script:assertions++
    if ($Expected -ne $Actual) {
        throw "PRODUCT_ASSERTION_FAILED $Name expected=$Expected actual=$Actual"
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Name)
    $script:assertions++
    if (-not $Condition) {
        throw "PRODUCT_ASSERTION_FAILED $Name"
    }
}

function Get-FreePort {
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
        $script:ports.Add($port)
        return $port
    } finally {
        $listener.Stop()
    }
}

function Wait-Mysql {
    for ($i = 0; $i -lt 90; $i++) {
        $oldPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $logs = (& docker logs $mysqlName 2>&1 | Out-String)
            $probe = (& docker exec --env "MYSQL_PWD=$databasePassword" $mysqlName mysql --user=root --batch --skip-column-names --execute 'SELECT 1' 2>&1 | Out-String).Trim()
            $code = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $oldPreference
        }
        if ($logs -match 'MySQL init process done\. Ready for start up\.' -and $code -eq 0 -and $probe -eq '1') {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw 'RUNNER_ENVIRONMENT_FAILED MySQL did not become ready'
}

function Start-Jar {
    param([string]$Jar, [int]$Port, [string]$Label, [string[]]$ExtraArgs)
    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
        throw "RUNNER_ENVIRONMENT_FAILED missing fat Jar: $Jar"
    }
    $stdout = Join-Path $evidence "$Label.stdout.log"
    $stderr = Join-Path $evidence "$Label.stderr.log"
    $jvm = @('-Xms16m', '-Xmx224m', '-XX:MaxMetaspaceSize=160m', '-XX:ReservedCodeCacheSize=48m', '-Xss512k', '-Xshare:off', '-jar', $Jar, "--server.port=$Port")
    $arguments = @($jvm + $ExtraArgs)
    $process = Start-Process -FilePath 'java' -ArgumentList $arguments -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    $script:processes.Add($process)
    return $process
}

function Stop-TaskProcess {
    param([AllowNull()][System.Diagnostics.Process]$Process)
    if ($null -ne $Process -and -not $Process.HasExited) {
        $Process.Kill()
        $Process.WaitForExit(10000) | Out-Null
    }
}

function Wait-Http {
    param([string]$Url, [int]$Seconds)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = $client.GetAsync($Url).GetAwaiter().GetResult()
            try {
                if ([int]$response.StatusCode -eq 200) {
                    return $true
                }
            } finally {
                $response.Dispose()
            }
        } catch {
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function ConvertTo-Base64Url {
    param([byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-Jwt {
    param([long]$UserId, [string]$Username, [string]$Secret, [long]$IssuedAt, [long]$ExpiresAt)
    $header = '{"alg":"HS256"}'
    $payload = "{`"userId`":$UserId,`"username`":`"$Username`",`"iat`":$IssuedAt,`"exp`":$ExpiresAt}"
    $encodedHeader = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))
    $encodedPayload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payload))
    $unsigned = "$encodedHeader.$encodedPayload"
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    try {
        $hmac.Key = [Text.Encoding]::UTF8.GetBytes($Secret)
        $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned)))
    } finally {
        $hmac.Dispose()
    }
    return "$unsigned.$signature"
}

function New-Request {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Url,
        [AllowNull()][string]$Authorization,
        [AllowNull()][object]$Body,
        [AllowNull()][string]$Key
    )
    $normalizedMethod = $Method.ToUpperInvariant()
    if (($normalizedMethod -eq 'GET' -or $normalizedMethod -eq 'HEAD') -and $null -ne $Body) {
        throw "RUNNER_CONTRACT_FAILED $normalizedMethod must not carry a body"
    }
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($normalizedMethod), $Url)
    if ($null -ne $Authorization) {
        $request.Headers.TryAddWithoutValidation('Authorization', $Authorization) | Out-Null
    }
    if ($null -ne $Key) {
        $request.Headers.TryAddWithoutValidation('Idempotency-Key', $Key) | Out-Null
    }
    if ($null -ne $Body) {
        $request.Content = New-Object System.Net.Http.StringContent(([string]$Body), [Text.Encoding]::UTF8, 'application/json')
    }
    return $request
}

function Send-Request {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Url,
        [AllowNull()][string]$Authorization,
        [AllowNull()][object]$Body,
        [AllowNull()][string]$Key
    )
    $request = New-Request -Method $Method -Url $Url -Authorization $Authorization -Body $Body -Key $Key
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            return [pscustomobject]@{
                Status = [int]$response.StatusCode
                Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                ContentType = if ($null -eq $response.Content.Headers.ContentType) { $null } else { $response.Content.Headers.ContentType.MediaType }
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }
}

function Assert-ErrorEnvelope {
    param([object]$Response, [int]$Status, [string]$Code, [string]$Name)
    Assert-Equal $Status $Response.Status "$Name.status"
    Assert-Equal 'application/json' $Response.ContentType "$Name.contentType"
    $json = $Response.Body | ConvertFrom-Json
    Assert-Equal $Code $json.errorCode "$Name.errorCode"
    $properties = @($json.psobject.Properties.Name | Sort-Object)
    Assert-Equal 'details,errorCode,message,requestId' ($properties -join ',') "$Name.fields"
    Assert-True ($json.requestId -match '^req_[0-9a-f]{32}$') "$Name.requestId"
    return $json
}

function Send-ConcurrentBatch {
    param([int]$Count, [string[]]$Bases, [string]$Token, [string]$Key, [string]$Body)
    $tasks = @()
    $requests = @()
    for ($i = 0; $i -lt $Count; $i++) {
        $request = New-Request -Method 'POST' -Url ($Bases[$i % $Bases.Count] + '/api/review-m0/cases') -Authorization "Bearer $Token" -Body $Body -Key $Key
        $requests += $request
        $tasks += $client.SendAsync($request)
    }
    $results = @()
    try {
        foreach ($task in $tasks) {
            $response = $task.GetAwaiter().GetResult()
            try {
                $results += [pscustomobject]@{
                    Status = [int]$response.StatusCode
                    Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
            } finally {
                $response.Dispose()
            }
        }
    } finally {
        foreach ($request in $requests) {
            $request.Dispose()
        }
    }
    return $results
}

function Invoke-LogScan {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\review-m0\run-a-fix2-runtime-log-scan.ps1') -LogPath $evidence -RequireDiagnostic
    if ($LASTEXITCODE -ne 0) {
        throw "PRODUCT_ASSERTION_FAILED runtime log scan exit=$LASTEXITCODE"
    }
}

$oldProxyEnvironment = @{}
foreach ($name in @('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'NO_PROXY', 'no_proxy')) {
    $oldProxyEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
[Environment]::SetEnvironmentVariable('NO_PROXY', '127.0.0.1,localhost', 'Process')
[Environment]::SetEnvironmentVariable('no_proxy', '127.0.0.1,localhost', 'Process')

try {
    if (Test-Path -LiteralPath $evidence) {
        throw "RUNNER_ENVIRONMENT_FAILED evidence directory already exists: $evidence"
    }
    New-Item -ItemType Directory -Path $evidence | Out-Null

    $mysqlPort = Get-FreePort
    & docker volume create $volumeName | Out-Null
    & docker run --detach --name $mysqlName --memory 512m --publish "127.0.0.1:$mysqlPort`:3306" --env "MYSQL_ROOT_PASSWORD=$databasePassword" --env "MYSQL_DATABASE=$databaseName" --mount "type=volume,source=$volumeName,target=/var/lib/mysql" mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --innodb-buffer-pool-size=64M --performance-schema=OFF | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "RUNNER_ENVIRONMENT_FAILED docker run exit=$LASTEXITCODE"
    }
    Wait-Mysql
    $mysqlVersion = (& docker exec --env "MYSQL_PWD=$databasePassword" $mysqlName mysql --user=root --batch --skip-column-names --execute 'SELECT VERSION()' | Out-String).Trim()

    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:MYSQL_LOCAL_HOST = '127.0.0.1'
    $env:MYSQL_LOCAL_PORT = "$mysqlPort"
    $env:MYSQL_LOCAL_USERNAME = 'root'
    $env:MYSQL_LOCAL_PASSWORD = $databasePassword
    $env:MYSQL_DATABASE = $databaseName
    $env:OMNIDOC_JWT_SECRET = $jwtSecret
    $env:DASHSCOPE_API_KEY = 'disabled-local-only'
    $env:OPENAI_API_KEY = 'disabled-local-only'
    $env:RABBITMQ_USERNAME = 'disabled-local-only'
    $env:RABBITMQ_PASSWORD = 'disabled-local-only'
    $env:MINIO_ACCESS_KEY = 'disabled-local-only'
    $env:MINIO_SECRET_KEY = 'disabled-local-only'
    $env:MINIO_LOCAL_ENDPOINT = 'http://127.0.0.1:9'
    $env:REDIS_LOCAL_HOST = '127.0.0.1'
    $env:REDIS_PORT = '9'

    $filePort = Get-FreePort
    $fileJar = Join-Path $root 'file-service\target\file-service-1.0.0.jar'
    $fileArgs = @('--spring.rabbitmq.listener.simple.auto-startup=false', '--spring.rabbitmq.listener.direct.auto-startup=false', '--spring.cloud.nacos.discovery.enabled=false', '--spring.cloud.nacos.registry.enabled=false')
    $fileProcess = Start-Jar -Jar $fileJar -Port $filePort -Label 'file' -ExtraArgs $fileArgs
    Assert-True (Wait-Http -Url "http://127.0.0.1:$filePort/v3/api-docs" -Seconds 90) 'file.httpReady'
    Stop-TaskProcess $fileProcess

    $aiJar = Join-Path $root 'ai-service\target\ai-service-1.0.0.jar'
    $aiArgs = @(
        '--ai.review-m0.persistence.enabled=true',
        '--ai.review-m0.persistence.schema.auto-migration.enabled=true',
        '--ai.memory.enabled=false',
        '--ai.aiops.scheduling.enabled=false',
        '--agent.mcp.enabled=false',
        '--spring.rabbitmq.listener.simple.auto-startup=false',
        '--spring.rabbitmq.listener.direct.auto-startup=false',
        '--spring.cloud.nacos.discovery.enabled=false',
        '--spring.cloud.nacos.registry.enabled=false',
        '--ai.models.openai.qwen3.7-max-2026-05-20.enabled=false',
        '--ai.models.openai.glm-5.enabled=false',
        '--ai.models.openai.kimi-k2.5.enabled=false',
        '--ai.models.openai.MiniMax-M2.5.enabled=false'
    )
    $aiPort1 = Get-FreePort
    $ai1 = Start-Jar -Jar $aiJar -Port $aiPort1 -Label 'ai1' -ExtraArgs $aiArgs
    Assert-True (Wait-Http -Url "http://127.0.0.1:$aiPort1/api-docs" -Seconds 120) 'ai1.httpReady'
    $aiPort2 = Get-FreePort
    $ai2 = Start-Jar -Jar $aiJar -Port $aiPort2 -Label 'ai2' -ExtraArgs $aiArgs
    Assert-True (Wait-Http -Url "http://127.0.0.1:$aiPort2/api-docs" -Seconds 120) 'ai2.httpReady'
    $base1 = "http://127.0.0.1:$aiPort1"
    $base2 = "http://127.0.0.1:$aiPort2"

    $caseBody = if ($Mode -eq 'Preflight') { '{"title":"R4 A R4 preflight","processingMode":"LOCAL_ONLY"}' } else { '{"title":"R4 A R4 native case","processingMode":"LOCAL_ONLY"}' }
    $getProbe = Send-Request -Method 'GET' -Url "$base1/api-docs" -Authorization $null -Body $null -Key $null
    Assert-Equal 200 $getProbe.Status 'request.getWithoutBody'
    $postProbe = Send-Request -Method 'POST' -Url "$base1/api/review-m0/cases" -Authorization $null -Body $caseBody -Key 'missing-auth-probe'
    Assert-ErrorEnvelope -Response $postProbe -Status 401 -Code 'AUTHENTICATION_REQUIRED' -Name 'request.postEnvelope' | Out-Null

    if ($Mode -eq 'Preflight') {
        Stop-TaskProcess $ai1
        Stop-TaskProcess $ai2
        Invoke-LogScan
        $result = [ordered]@{
            status = 'PREFLIGHT_PASS'
            assertions = $assertions
            mysqlVersion = $mysqlVersion
            randomPorts = @($ports)
            servicesReady = 'file/ai1/ai2'
            getWithoutBody = 200
            postEnvelope = '401/AUTHENTICATION_REQUIRED'
            proxy = 'BYPASSED'
            localOnly = $true
            p08 = 'DISABLED'
            logScan = 'PASS'
        }
        $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'preflight-result.json') -Encoding utf8
        $result | ConvertTo-Json -Depth 6
        return
    }

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $ownerA = New-Jwt 74001 'r4ar4-owner-a' $jwtSecret $now ($now + 1800)
    $ownerB = New-Jwt 74002 'r4ar4-owner-b' $jwtSecret $now ($now + 1800)
    $wrong = New-Jwt 74001 'r4ar4-owner-a' ($jwtSecret + 'wrong') $now ($now + 1800)
    $expired = New-Jwt 74001 'r4ar4-owner-a' $jwtSecret ($now - 3600) ($now - 1800)

    $authCases = @(
        @{ Name = 'missing'; Auth = $null },
        @{ Name = 'emptyBearer'; Auth = 'Bearer ' },
        @{ Name = 'nonBearer'; Auth = 'Basic synthetic' },
        @{ Name = 'wrongSignature'; Auth = "Bearer $wrong" },
        @{ Name = 'expired'; Auth = "Bearer $expired" },
        @{ Name = 'malformed'; Auth = 'Bearer abc.def.ghi' },
        @{ Name = 'parseError'; Auth = 'Bearer eyJhbGciOiJIUzI1NiJ9.e30.bad' }
    )
    $authMessages = @()
    foreach ($authCase in $authCases) {
        $response = Send-Request -Method 'POST' -Url "$base1/api/review-m0/cases" -Authorization $authCase.Auth -Body $caseBody -Key ("auth-" + $authCase.Name)
        $json = Assert-ErrorEnvelope -Response $response -Status 401 -Code 'AUTHENTICATION_REQUIRED' -Name ("auth." + $authCase.Name)
        $authMessages += $json.message
    }
    Assert-Equal 1 (@($authMessages | Select-Object -Unique).Count) 'auth.uniformMessage'

    $create = Send-Request -Method 'POST' -Url "$base1/api/review-m0/cases" -Authorization "Bearer $ownerA" -Body $caseBody -Key 'owner-create'
    Assert-Equal 201 $create.Status 'owner.create'
    $caseId = ($create.Body | ConvertFrom-Json).caseId
    Assert-True (-not [string]::IsNullOrWhiteSpace($caseId)) 'owner.caseId'
    $read = Send-Request -Method 'GET' -Url "$base2/api/review-m0/cases/$caseId" -Authorization "Bearer $ownerA" -Body $null -Key $null
    Assert-Equal 200 $read.Status 'owner.read'
    Assert-ErrorEnvelope -Response (Send-Request -Method 'GET' -Url "$base2/api/review-m0/cases/$caseId" -Authorization "Bearer $ownerB" -Body $null -Key $null) -Status 404 -Code 'REVIEW_M0_CASE_NOT_FOUND' -Name 'owner.cross' | Out-Null
    Assert-ErrorEnvelope -Response (Send-Request -Method 'GET' -Url "$base1/api/review-m0/cases/case_missing_r4ar4" -Authorization "Bearer $ownerA" -Body $null -Key $null) -Status 404 -Code 'REVIEW_M0_CASE_NOT_FOUND' -Name 'owner.missing' | Out-Null
    Assert-Equal 403 (Send-Request -Method 'GET' -Url "$base1/api/ai/conversations" -Authorization $null -Body $null -Key $null).Status 'nonReview.protected'
    Assert-Equal 200 (Send-Request -Method 'GET' -Url "$base1/api-docs" -Authorization $null -Body $null -Key $null).Status 'permitAll.apiDocs'

    $sequential = Send-Request -Method 'POST' -Url "$base1/api/review-m0/cases" -Authorization "Bearer $ownerA" -Body $caseBody -Key 'sequential-key'
    Assert-Equal 201 $sequential.Status 'idempotency.sequential.first'
    $sequentialReplay = Send-Request -Method 'POST' -Url "$base2/api/review-m0/cases" -Authorization "Bearer $ownerA" -Body $caseBody -Key 'sequential-key'
    Assert-Equal 201 $sequentialReplay.Status 'idempotency.sequential.replayStatus'
    Assert-Equal $sequential.Body $sequentialReplay.Body 'idempotency.sequential.replayBody'
    $differentBody = '{"title":"R4 A R4 different payload","processingMode":"LOCAL_ONLY"}'
    Assert-ErrorEnvelope -Response (Send-Request -Method 'POST' -Url "$base1/api/review-m0/cases" -Authorization "Bearer $ownerA" -Body $differentBody -Key 'sequential-key') -Status 409 -Code 'REVIEW_M0_IDEMPOTENCY_CONFLICT' -Name 'idempotency.payloadConflict' | Out-Null

    $batchSummary = @()
    foreach ($count in @(8, 16, 48)) {
        $key = "concurrent-$count"
        $results = Send-ConcurrentBatch -Count $count -Bases @($base1, $base2) -Token $ownerA -Key $key -Body $caseBody
        $distinctBodies = @($results.Body | Select-Object -Unique).Count
        Assert-Equal $count (@($results | Where-Object Status -eq 201).Count) "concurrent.$count.status201"
        Assert-Equal 0 (@($results | Where-Object Status -eq 500).Count) "concurrent.$count.status500"
        Assert-Equal 0 (@($results | Where-Object Status -eq 503).Count) "concurrent.$count.status503"
        Assert-Equal 1 $distinctBodies "concurrent.$count.distinctBody"
        $responseCaseId = ($results[0].Body | ConvertFrom-Json).caseId
        $sql = "SELECT (SELECT COUNT(*) FROM review_m0_case WHERE case_id='$responseCaseId'), (SELECT COUNT(*) FROM review_m0_idempotency WHERE idempotency_key='$key' AND state='COMPLETED'), (SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot WHERE idempotency_key='$key')"
        $counts = (& docker exec --env "MYSQL_PWD=$databasePassword" $mysqlName mysql --user=root --database=$databaseName --batch --skip-column-names --execute $sql | Out-String).Trim()
        Assert-Equal "1`t1`t1" $counts "concurrent.$count.databaseCounts"
        $batchSummary += [pscustomobject]@{ Concurrency = $count; Status201 = $count; Status500 = 0; Status503 = 0; DistinctBodies = $distinctBodies; FirstBody = $results[0].Body; DatabaseCounts = $counts }
    }

    & docker stop $mysqlName | Out-Null
    $unavailable = @()
    for ($i = 1; $i -le 3; $i++) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = Send-Request -Method 'POST' -Url "$base1/api/review-m0/cases" -Authorization "Bearer $ownerA" -Body $caseBody -Key "db-down-$i"
        $watch.Stop()
        $json = Assert-ErrorEnvelope -Response $response -Status 503 -Code 'REVIEW_M0_STORAGE_UNAVAILABLE' -Name "dbUnavailable.$i"
        Assert-True ($watch.ElapsedMilliseconds -lt 15000) "dbUnavailable.$i.bounded"
        $unavailable += [pscustomobject]@{ Attempt = $i; ElapsedMs = $watch.ElapsedMilliseconds; Status = $response.Status; Code = $json.errorCode }
    }

    $startupPort = Get-FreePort
    $startupFail = Start-Jar -Jar $aiJar -Port $startupPort -Label 'ai-dbdown-startup' -ExtraArgs $aiArgs
    $startupReady = Wait-Http -Url "http://127.0.0.1:$startupPort/api-docs" -Seconds 15
    Assert-True (-not $startupReady) 'dbUnavailable.startupFailClosed'
    Stop-TaskProcess $startupFail

    & docker start $mysqlName | Out-Null
    Wait-Mysql
    $recovered = Send-Request -Method 'POST' -Url "$base1/api/review-m0/cases" -Authorization "Bearer $ownerA" -Body $caseBody -Key 'recovery-new-write'
    Assert-Equal 201 $recovered.Status 'dbRecovery.sameProcessWrite'
    $failedResidue = (& docker exec --env "MYSQL_PWD=$databasePassword" $mysqlName mysql --user=root --database=$databaseName --batch --skip-column-names --execute "SELECT (SELECT COUNT(*) FROM review_m0_idempotency WHERE idempotency_key LIKE 'db-down-%'), (SELECT COUNT(*) FROM review_m0_idempotency_response_snapshot WHERE idempotency_key LIKE 'db-down-%')" | Out-String).Trim()
    Assert-Equal "0`t0" $failedResidue 'dbUnavailable.noIdempotencyResidue'

    Stop-TaskProcess $ai1
    Stop-TaskProcess $ai2
    & docker restart $mysqlName | Out-Null
    Wait-Mysql
    $restartPort = Get-FreePort
    $aiRestart = Start-Jar -Jar $aiJar -Port $restartPort -Label 'ai-restart' -ExtraArgs $aiArgs
    Assert-True (Wait-Http -Url "http://127.0.0.1:$restartPort/api-docs" -Seconds 120) 'aiRestart.httpReady'
    $replay48 = Send-Request -Method 'POST' -Url "http://127.0.0.1:$restartPort/api/review-m0/cases" -Authorization "Bearer $ownerA" -Body $caseBody -Key 'concurrent-48'
    Assert-Equal 201 $replay48.Status 'restartReplay.status'
    Assert-Equal $batchSummary[2].FirstBody $replay48.Body 'restartReplay.body'

    $schema = (& docker exec --env "MYSQL_PWD=$databasePassword" $mysqlName mysql --user=root --database=$databaseName --batch --skip-column-names --execute 'SELECT MAX(version), COUNT(*) FROM review_m0_schema_version' | Out-String).Trim()
    Assert-Equal "3`t3" $schema 'schema.versionRows'

    Stop-TaskProcess $aiRestart
    Invoke-LogScan
    $result = [ordered]@{
        status = 'NATIVE_GATE_PASS'
        assertions = $assertions
        mysqlVersion = $mysqlVersion
        schema = $schema
        randomPorts = @($ports)
        fileReady = 1
        aiReady = 3
        auth401 = 7
        owner = '201/200/404/404'
        nonReviewProtected = 403
        sequentialReplay = '201/same-body'
        payloadConflict = 409
        concurrency = $batchSummary | Select-Object Concurrency, Status201, Status500, Status503, DistinctBodies, DatabaseCounts
        databaseUnavailable = $unavailable
        startupFailClosed = $true
        recovery = 201
        failedResidue = $failedResidue
        restartReplay = '201/same-body'
        proxy = 'BYPASSED'
        localOnly = $true
        p08 = 'DISABLED'
        logScan = 'PASS'
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'native-result.json') -Encoding utf8
    $result | ConvertTo-Json -Depth 8
} finally {
    foreach ($process in $processes) {
        Stop-TaskProcess $process
    }
    $client.Dispose()
    $handler.Dispose()
    Remove-Item Env:\SPRING_PROFILES_ACTIVE, Env:\MYSQL_LOCAL_HOST, Env:\MYSQL_LOCAL_PORT, Env:\MYSQL_LOCAL_USERNAME, Env:\MYSQL_LOCAL_PASSWORD, Env:\MYSQL_DATABASE, Env:\OMNIDOC_JWT_SECRET, Env:\DASHSCOPE_API_KEY, Env:\OPENAI_API_KEY, Env:\RABBITMQ_USERNAME, Env:\RABBITMQ_PASSWORD, Env:\MINIO_ACCESS_KEY, Env:\MINIO_SECRET_KEY, Env:\MINIO_LOCAL_ENDPOINT, Env:\REDIS_LOCAL_HOST, Env:\REDIS_PORT -ErrorAction SilentlyContinue
    foreach ($name in $oldProxyEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $oldProxyEnvironment[$name], 'Process')
    }
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    & docker rm --force $mysqlName 2>$null | Out-Null
    & docker volume rm $volumeName 2>$null | Out-Null
    $ErrorActionPreference = $oldPreference
}
