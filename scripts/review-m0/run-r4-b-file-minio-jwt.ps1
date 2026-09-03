param(
    [ValidateSet('Probe', 'Formal')]
    [string]$Mode = 'Probe',
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9]{6,12}$')]
    [string]$ResourceSuffix
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$evidence = [IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))
$prefix = if ($Mode -eq 'Probe') { "r4br3p$ResourceSuffix" } else { "r4br3f$ResourceSuffix" }
$mysqlName = "${prefix}_mysql"
$minioName = "${prefix}_minio"
$networkName = "${prefix}_net"
$mysqlVolume = "${prefix}_mysql_data"
$minioVolume = "${prefix}_minio_data"
$databaseName = "review_${prefix}"
$mysqlPassword = [Guid]::NewGuid().ToString('N')
$minioAccess = 'r4b' + [Guid]::NewGuid().ToString('N').Substring(0, 16)
$minioSecret = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$jwtSecret = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$bucket = 'omnidoc'
$processes = New-Object 'System.Collections.Generic.List[System.Diagnostics.Process]'
$ports = New-Object 'System.Collections.Generic.List[int]'
$assertions = 0
$statuses = [ordered]@{}
$fileProcess = $null
$aiProcess = $null
$clientHandler = New-Object System.Net.Http.HttpClientHandler
$clientHandler.UseProxy = $false
$clientHandler.Proxy = $null
$client = New-Object System.Net.Http.HttpClient($clientHandler)
$client.Timeout = [TimeSpan]::FromSeconds(40)

function Assert-Equal($Expected, $Actual, [string]$Name) {
    $script:assertions++
    if ($Expected -ne $Actual) { throw "PRODUCT_ASSERTION_FAILED $Name expected=$Expected actual=$Actual" }
}

function Assert-True([bool]$Condition, [string]$Name) {
    $script:assertions++
    if (-not $Condition) { throw "PRODUCT_ASSERTION_FAILED $Name" }
}

function Get-FreePort {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
        $script:ports.Add($port)
        return $port
    } finally { $listener.Stop() }
}

function Invoke-Docker([string[]]$Arguments, [string]$Failure) {
    & docker @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "RUNNER_ENVIRONMENT_FAILED $Failure exit=$LASTEXITCODE" }
}

function Wait-Mysql {
    for ($i = 0; $i -lt 120; $i++) {
        $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try {
            $logs = (& docker logs $mysqlName 2>&1 | Out-String)
            $probe = (& docker exec --env "MYSQL_PWD=$mysqlPassword" $mysqlName mysql --user=root --batch --skip-column-names --execute 'SELECT 1' 2>&1 | Out-String).Trim()
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $old }
        if ($logs -match 'MySQL init process done\. Ready for start up\.' -and $code -eq 0 -and $probe -eq '1') { return }
        Start-Sleep -Seconds 1
    }
    throw 'RUNNER_ENVIRONMENT_FAILED MySQL did not become ready'
}

function Wait-Minio([int]$Port) {
    for ($i = 0; $i -lt 120; $i++) {
        try {
            $response = $client.GetAsync("http://127.0.0.1:$Port/minio/health/ready").GetAwaiter().GetResult()
            try { if ([int]$response.StatusCode -eq 200) { return } } finally { $response.Dispose() }
        } catch {}
        Start-Sleep -Seconds 1
    }
    throw 'RUNNER_ENVIRONMENT_FAILED MinIO did not become ready'
}

function Start-Jar([string]$Jar, [int]$Port, [string]$Label, [string[]]$ExtraArgs) {
    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw "RUNNER_ENVIRONMENT_FAILED missing fat Jar $Label" }
    $stdout = Join-Path $evidence "$Label.stdout.log"
    $stderr = Join-Path $evidence "$Label.stderr.log"
    $args = @('-Xms16m','-Xmx256m','-XX:MaxMetaspaceSize=160m','-XX:ReservedCodeCacheSize=48m','-Xss512k','-Xshare:off','-jar',$Jar,"--server.port=$Port") + $ExtraArgs
    $process = Start-Process -FilePath 'java' -ArgumentList $args -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    $script:processes.Add($process)
    return $process
}

function Stop-TaskProcess([AllowNull()][Diagnostics.Process]$Process) {
    if ($null -ne $Process -and -not $Process.HasExited) {
        $Process.Kill(); $Process.WaitForExit(15000) | Out-Null
    }
}

function Wait-Http([string]$Url, [int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = $client.GetAsync($Url).GetAwaiter().GetResult()
            try { if ([int]$response.StatusCode -eq 200) { return $true } } finally { $response.Dispose() }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function ConvertTo-Base64Url([byte[]]$Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

function Get-Sha256Hex([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
}

function Assert-NoSuccessIntegrityHeaders($Response, [string]$Name) {
    foreach ($header in @('ETag','Digest','X-Content-SHA256')) {
        Assert-True (-not $Response.Headers.ContainsKey($header)) "$Name.no.$header"
    }
}

function Assert-IntegrityHeaders($Response, [string]$Name) {
    Assert-True ($Response.Headers.ContainsKey('ETag') -and $Response.Headers.ContainsKey('Digest') -and $Response.Headers.ContainsKey('X-Content-SHA256')) "$Name.present"
    $hex = Get-Sha256Hex ([byte[]]$Response.Bytes)
    $expectedEtag = '"' + $hex + '"'
    $rawDigest = New-Object byte[] ($hex.Length / 2)
    for ($i = 0; $i -lt $rawDigest.Length; $i++) { $rawDigest[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16) }
    $expectedDigest = 'sha-256=' + [Convert]::ToBase64String($rawDigest)
    Assert-True ([string]$Response.Headers['ETag'] -ceq $expectedEtag) "$Name.etagMatchesBody"
    Assert-True ([string]$Response.Headers['X-Content-SHA256'] -ceq $hex) "$Name.xContentShaMatchesBody"
    Assert-True ([string]$Response.Headers['Digest'] -ceq $expectedDigest) "$Name.digestMatchesBody"
}

function Assert-SafeContentDisposition($Response, [string]$ExpectedName, [string]$Name) {
    $value = [string]$Response.Headers['Content-Disposition']
    Assert-True (-not [string]::IsNullOrWhiteSpace($value)) "$Name.present"
    Assert-True ($value -notmatch '[\r\n]') "$Name.noInjection"
    $match = [regex]::Match($value, "filename\*\s*=\s*UTF-8''([^;]+)", [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    Assert-True $match.Success "$Name.filenameStar"
    Assert-True ([Uri]::UnescapeDataString($match.Groups[1].Value) -ceq $ExpectedName) "$Name.logicalName"
}

function New-Jwt([long]$UserId, [string]$Secret, [long]$IssuedAt, [long]$ExpiresAt) {
    $header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256"}'))
    $payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes("{`"userId`":$UserId,`"username`":`"synthetic`",`"iat`":$IssuedAt,`"exp`":$ExpiresAt}"))
    $unsigned = "$header.$payload"
    $hmac = New-Object Security.Cryptography.HMACSHA256
    try {
        $hmac.Key = [Text.Encoding]::UTF8.GetBytes($Secret)
        $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned)))
    } finally { $hmac.Dispose() }
    return "$unsigned.$signature"
}

function Send-Json([string]$Method, [string]$Url, [AllowNull()][string]$Token, [AllowNull()][object]$Body, [AllowNull()][string]$Key, [hashtable]$Headers = @{}) {
    $request = New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::new($Method), $Url)
    try {
        if ($null -ne $Token) { $request.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token") | Out-Null }
        if ($null -ne $Key) { $request.Headers.TryAddWithoutValidation('Idempotency-Key', $Key) | Out-Null }
        foreach ($name in $Headers.Keys) { $request.Headers.TryAddWithoutValidation($name, [string]$Headers[$name]) | Out-Null }
        if ($null -ne $Body) { $request.Content = New-Object Net.Http.StringContent(($Body | ConvertTo-Json -Compress -Depth 20), [Text.Encoding]::UTF8, 'application/json') }
        $response = $client.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        try {
            $readError = $false
            try {
                $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            } catch {
                $bytes = [byte[]]::new(0)
                $readError = $true
            }
            $headers = @{}
            foreach ($pair in $response.Headers) { $headers[$pair.Key] = ($pair.Value -join ',') }
            foreach ($pair in $response.Content.Headers) { $headers[$pair.Key] = ($pair.Value -join ',') }
            return [pscustomobject]@{ Status=[int]$response.StatusCode; Bytes=$bytes; Body=[Text.Encoding]::UTF8.GetString($bytes); Headers=$headers; ReadError=$readError }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}

function Send-Multipart([string]$Url, [AllowNull()][string]$Token, [string]$Path, [hashtable]$Headers = @{}) {
    $request = New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::Post, $Url)
    $multipart = New-Object Net.Http.MultipartFormDataContent
    try {
        if ($null -ne $Token) { $request.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token") | Out-Null }
        foreach ($name in $Headers.Keys) { $request.Headers.TryAddWithoutValidation($name, [string]$Headers[$name]) | Out-Null }
        $content = [Net.Http.ByteArrayContent]::new([byte[]][IO.File]::ReadAllBytes($Path))
        $mediaType = switch ([IO.Path]::GetExtension($Path).ToLowerInvariant()) {
            '.pdf' { 'application/pdf' }
            '.docx' { 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }
            '.xlsx' { 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }
            '.jpg' { 'image/jpeg' }
            '.png' { 'image/png' }
            '.zip' { 'application/zip' }
            default { 'application/octet-stream' }
        }
        $content.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new($mediaType)
        $multipart.Add($content, 'file', [IO.Path]::GetFileName($Path))
        $request.Content = $multipart
        $response = $client.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        try {
            $readError = $false
            try {
                $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            } catch {
                $bytes = [byte[]]::new(0)
                $readError = $true
            }
            $responseHeaders = @{}
            foreach ($pair in $response.Headers) { $responseHeaders[$pair.Key] = ($pair.Value -join ',') }
            foreach ($pair in $response.Content.Headers) { $responseHeaders[$pair.Key] = ($pair.Value -join ',') }
            return [pscustomobject]@{ Status=[int]$response.StatusCode; Bytes=$bytes; Body=[Text.Encoding]::UTF8.GetString($bytes); Headers=$responseHeaders; ReadError=$readError }
        } finally { $response.Dispose() }
    } finally { $request.Dispose(); $multipart.Dispose() }
}

function Write-Zip([string]$Path, [hashtable]$Entries) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $false, [Text.Encoding]::UTF8)
        try {
            foreach ($name in $Entries.Keys) {
                $entry = $archive.CreateEntry($name, [IO.Compression.CompressionLevel]::Fastest)
                $target = $entry.Open()
                try { $bytes = [byte[]]$Entries[$name]; $target.Write($bytes, 0, $bytes.Length) } finally { $target.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
}

function New-Fixtures([string]$Directory) {
    New-Item -ItemType Directory -Path $Directory | Out-Null
    $pdf = [Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n1 0 obj`n<< /R4B_SYNTHETIC_BODY_CANARY true >>`nendobj`n%%EOF")
    $wrongPdf = [Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n1 0 obj`n<< /R4B_WRONG_LEAF true >>`nendobj`n%%EOF")
    $selectedPdf = [Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n1 0 obj`n<< /R4B_SELECTED_LEAF true >>`nendobj`n%%EOF")
    $jpg = [byte[]](0xff,0xd8,0xff,0xe0,0x00,0x02,0xff,0xd9)
    $png = [byte[]](0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,0x00,0x00,0x00,0x00,0x49,0x45,0x4e,0x44,0x00,0x00,0x00,0x00)
    [IO.File]::WriteAllBytes((Join-Path $Directory 'synthetic.pdf'), $pdf)
    [IO.File]::WriteAllBytes((Join-Path $Directory 'zip-wrong.pdf'), $wrongPdf)
    [IO.File]::WriteAllBytes((Join-Path $Directory 'zip-selected.pdf'), $selectedPdf)
    [IO.File]::WriteAllBytes((Join-Path $Directory 'synthetic.jpg'), $jpg)
    [IO.File]::WriteAllBytes((Join-Path $Directory 'synthetic.png'), $png)
    $docx = Join-Path $Directory 'synthetic.docx'
    $xlsx = Join-Path $Directory 'synthetic.xlsx'
    Write-Zip $docx @{ '[Content_Types].xml'=[Text.Encoding]::UTF8.GetBytes('<Types/>'); 'word/document.xml'=[Text.Encoding]::UTF8.GetBytes('<document/>') }
    Write-Zip $xlsx @{ '[Content_Types].xml'=[Text.Encoding]::UTF8.GetBytes('<Types/>'); 'xl/workbook.xml'=[Text.Encoding]::UTF8.GetBytes('<workbook/>') }
    Write-Zip (Join-Path $Directory 'synthetic.zip') @{
        'leaf/0-wrong.pdf'=$wrongPdf; 'leaf/1-selected.pdf'=$selectedPdf; 'leaf/2-document.docx'=[IO.File]::ReadAllBytes($docx); 'leaf/3-workbook.xlsx'=[IO.File]::ReadAllBytes($xlsx); 'leaf/4-photo.jpg'=$jpg; 'leaf/5-image.png'=$png
    }
    $large = New-Object byte[] (2 * 1024 * 1024)
    [Array]::Copy($pdf, $large, $pdf.Length)
    [Array]::Copy([Text.Encoding]::ASCII.GetBytes('%%EOF'), 0, $large, $large.Length - 5, 5)
    [IO.File]::WriteAllBytes((Join-Path $Directory 'abort.pdf'), $large)
}

function Invoke-Mysql([string]$Sql, [switch]$Database) {
    $args = @('exec','--env',"MYSQL_PWD=$mysqlPassword",$mysqlName,'mysql','--user=root','--batch','--skip-column-names')
    if ($Database) { $args += "--database=$databaseName" }
    $args += @('--execute',$Sql)
    $output = (& docker @args | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'RUNNER_ENVIRONMENT_FAILED database command failed' }
    return $output
}

function Invoke-Sdk([string]$Action, [AllowNull()][string]$FileId, [AllowNull()][string]$Fixture) {
    $cpFile = Join-Path $root 'file-service\target\r4b-runtime-classpath.txt'
    $cp = (Get-Content -LiteralPath $cpFile -Raw).Trim()
    $classPath = (Join-Path $root 'file-service\target\test-classes') + ';' + $cp
    $args = @('-cp',$classPath,'com.javaee.fileservice.acceptance.R4BMinioSdkProbe',$Action)
    if ($null -ne $FileId) { $args += $FileId }
    if ($null -ne $Fixture) { $args += $Fixture }
    $result = (& java @args | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "PRODUCT_ASSERTION_FAILED minioSdk.$Action" }
    return ($result | ConvertFrom-Json)
}

function Assert-FailClosedUrl([string]$Url, [string]$Token, [string]$Name, [AllowNull()][byte[]]$ForbiddenBytes = $null) {
    $response = Send-Json -Method 'GET' -Url $Url -Token $Token -Body $null -Key $null
    Assert-Equal 503 $response.Status "$Name.status"
    Assert-True (-not $response.ReadError) "$Name.noReadError"
    Assert-True ($response.Bytes.Length -lt 4096) "$Name.noFullBytes"
    if ($null -ne $ForbiddenBytes) {
        Assert-True (-not [Linq.Enumerable]::SequenceEqual([byte[]]$response.Bytes,[byte[]]$ForbiddenBytes)) "$Name.notOriginalBytes"
    }
    Assert-NoSuccessIntegrityHeaders -Response $response -Name $Name
}

function Assert-FailClosed([string]$Base, [string]$Token, [string]$FileId, [string]$Name, [AllowNull()][byte[]]$ForbiddenBytes = $null) {
    Assert-FailClosedUrl -Url "$Base/api/files/owned/$FileId/content" -Token $Token -Name $Name -ForbiddenBytes $ForbiddenBytes
}

function Get-StagingPayloadCount([string]$Staging) {
    if (-not (Test-Path -LiteralPath $Staging)) { return 0 }
    return @(Get-ChildItem -LiteralPath $Staging -Recurse -File -Filter 'payload-*.tmp' -ErrorAction SilentlyContinue).Count
}

function Assert-LogSafety([string]$Directory) {
    $files = @(Get-ChildItem -LiteralPath $Directory -Recurse -File -Filter '*.log')
    Assert-True ($files.Count -ge 4) 'logs.filesPresent'
    $patterns = [ordered]@{
        material='R4B_SYNTHETIC_BODY_CANARY'; jwt='eyJ[a-zA-Z0-9_-]{8,}\.'; credential='(?i)(access.?key|secret.?key|password|credential)\s*[:=]\s*\S+';
        locator='(?i)(object.?key|bucket.?name)\s*[:=]\s*\S+'; hash='(?<![A-Fa-f0-9])[A-Fa-f0-9]{64}(?![A-Fa-f0-9])'; presigned='(?i)X-Amz-(Credential|Signature)'; sql='(?i)(select|insert|update|delete|alter|create)\s+.+(from|into|table|set)';
        stack='\s+at\s+[A-Za-z_$][\w.$]+\([^\r\n]+:\d+\)'; throwable='(?m)^[\w.$]+(?:Exception|Error|Throwable)(?::|$)'; generated='Using generated security password'; body='(?i)(request|response).?body\s*[:=]'
    }
    foreach ($name in $patterns.Keys) {
        $count = 0
        foreach ($file in $files) { $count += ([regex]::Matches((Get-Content -LiteralPath $file.FullName -Raw), $patterns[$name])).Count }
        Assert-Equal 0 $count "logs.$name"
    }
    $runtimeSensitiveValues = @($script:tokenA,$script:tokenB,$script:invalid,$script:expired,$script:mysqlPassword,$script:minioAccess,$script:minioSecret,$script:jwtSecret) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
    $runtimeSensitiveMatches = 0
    foreach ($file in $files) {
        $text = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($value in $runtimeSensitiveValues) {
            if ($text.Contains([string]$value)) { $runtimeSensitiveMatches++ }
        }
    }
    Assert-Equal 0 $runtimeSensitiveMatches 'logs.runtimeSensitiveValues'
}

$oldProxy = @{}
foreach ($name in @('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy','NO_PROXY','no_proxy')) {
    $oldProxy[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
[Environment]::SetEnvironmentVariable('NO_PROXY','127.0.0.1,localhost','Process')
[Environment]::SetEnvironmentVariable('no_proxy','127.0.0.1,localhost','Process')

try {
    if (Test-Path -LiteralPath $evidence) { throw "RUNNER_ENVIRONMENT_FAILED evidence directory already exists $evidence" }
    New-Item -ItemType Directory -Path $evidence | Out-Null
    $fixtures = Join-Path $evidence 'fixtures'
    $staging = Join-Path $evidence 'staging'
    New-Fixtures $fixtures
    $mysqlPort = Get-FreePort; $minioPort = Get-FreePort; $minioConsole = Get-FreePort; $filePort = Get-FreePort; $aiPort = Get-FreePort
    Invoke-Docker @('network','create',$networkName) 'network create'
    Invoke-Docker @('volume','create',$mysqlVolume) 'mysql volume create'
    Invoke-Docker @('volume','create',$minioVolume) 'minio volume create'
    Invoke-Docker @('run','--detach','--name',$mysqlName,'--network',$networkName,'--memory','512m','--publish',"127.0.0.1:$mysqlPort`:3306",'--env',"MYSQL_ROOT_PASSWORD=$mysqlPassword",'--env',"MYSQL_DATABASE=$databaseName",'--mount',"type=volume,source=$mysqlVolume,target=/var/lib/mysql",'mysql:8.0','--character-set-server=utf8mb4','--collation-server=utf8mb4_0900_ai_ci','--innodb-buffer-pool-size=64M','--performance-schema=OFF') 'mysql run'
    Invoke-Docker @('run','--detach','--name',$minioName,'--network',$networkName,'--memory','256m','--publish',"127.0.0.1:$minioPort`:9000",'--publish',"127.0.0.1:$minioConsole`:9001",'--env',"MINIO_ROOT_USER=$minioAccess",'--env',"MINIO_ROOT_PASSWORD=$minioSecret",'--mount',"type=volume,source=$minioVolume,target=/data",'minio/minio:RELEASE.2025-09-07T16-13-09Z','server','/data','--console-address',':9001') 'minio run'
    Wait-Mysql; Wait-Minio $minioPort
    $schemaDatabase = ([char]96) + $databaseName + ([char]96)
    $schema = (Get-Content -LiteralPath (Join-Path $root 'file-service\src\main\resources\file.sql') -Raw).Replace('`omnidoc`', $schemaDatabase)
    $schema | & docker exec --interactive --env "MYSQL_PWD=$mysqlPassword" $mysqlName mysql --user=root | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'RUNNER_ENVIRONMENT_FAILED file schema initialization failed' }

    $env:SPRING_PROFILES_ACTIVE='local'; $env:MYSQL_LOCAL_HOST='127.0.0.1'; $env:MYSQL_LOCAL_PORT="$mysqlPort"; $env:MYSQL_LOCAL_USERNAME='root'; $env:MYSQL_LOCAL_PASSWORD=$mysqlPassword
    $env:MYSQL_HOST='127.0.0.1'; $env:MYSQL_PORT="$mysqlPort"; $env:MYSQL_USERNAME='root'; $env:MYSQL_PASSWORD=$mysqlPassword; $env:MYSQL_DATABASE=$databaseName
    $env:MINIO_LOCAL_ENDPOINT="http://127.0.0.1:$minioPort"; $env:MINIO_ENDPOINT=$env:MINIO_LOCAL_ENDPOINT; $env:MINIO_ACCESS_KEY=$minioAccess; $env:MINIO_SECRET_KEY=$minioSecret
    $env:OMNIDOC_JWT_SECRET=$jwtSecret; $env:DASHSCOPE_API_KEY='disabled-local-only'; $env:OPENAI_API_KEY='disabled-local-only'; $env:RABBITMQ_USERNAME='disabled-local-only'; $env:RABBITMQ_PASSWORD='disabled-local-only'; $env:REDIS_LOCAL_HOST='127.0.0.1'; $env:REDIS_PORT='9'
    $env:R4B_JDBC_URL="jdbc:mysql://127.0.0.1:$mysqlPort/$databaseName`?useSSL=false&allowPublicKeyRetrieval=true"; $env:R4B_DB_USER='root'; $env:R4B_DB_PASSWORD=$mysqlPassword
    $env:R4B_MINIO_ENDPOINT=$env:MINIO_LOCAL_ENDPOINT; $env:R4B_MINIO_ACCESS=$minioAccess; $env:R4B_MINIO_SECRET=$minioSecret; $env:R4B_MINIO_BUCKET=$bucket

    $fileArgs = @('--spring.rabbitmq.listener.simple.auto-startup=false','--spring.rabbitmq.listener.direct.auto-startup=false','--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.registry.enabled=false',"--file.owned-download.staging-directory=$staging")
    $fileProcess = Start-Jar (Join-Path $root 'file-service\target\file-service-1.0.0.jar') $filePort 'file-1' $fileArgs
    Assert-True (Wait-Http "http://127.0.0.1:$filePort/v3/api-docs" 120) 'file.ready'
    $aiArgs = @('--ai.review-m0.persistence.enabled=true','--ai.review-m0.persistence.schema.auto-migration.enabled=true',"--ai.review-m0.runtime.file-service-base-uri=http://127.0.0.1:$filePort",'--ai.memory.enabled=false','--ai.aiops.scheduling.enabled=false','--agent.mcp.enabled=false','--spring.rabbitmq.listener.simple.auto-startup=false','--spring.rabbitmq.listener.direct.auto-startup=false','--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.registry.enabled=false','--ai.models.openai.qwen3.7-max-2026-05-20.enabled=false','--ai.models.openai.glm-5.enabled=false','--ai.models.openai.kimi-k2.5.enabled=false','--ai.models.openai.MiniMax-M2.5.enabled=false')
    $aiProcess = Start-Jar (Join-Path $root 'ai-service\target\ai-service-1.0.0.jar') $aiPort 'ai-1' $aiArgs
    Assert-True (Wait-Http "http://127.0.0.1:$aiPort/api-docs" 150) 'ai.ready'

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $tokenA = New-Jwt 85001 $jwtSecret $now ($now + 1800)
    $tokenB = New-Jwt 85002 $jwtSecret $now ($now + 1800)
    $invalid = New-Jwt 85001 ($jwtSecret + 'x') $now ($now + 1800)
    $expired = New-Jwt 85001 $jwtSecret ($now - 3600) ($now - 1800)
    $fileBase = "http://127.0.0.1:$filePort"; $aiBase = "http://127.0.0.1:$aiPort"
    Assert-Equal '1,2,3' (Invoke-Mysql 'SELECT GROUP_CONCAT(version ORDER BY version) FROM review_m0_schema_version' -Database) 'environment.reviewSchemaV3'

    $startedAt = [DateTimeOffset]::Now
    $formats = [ordered]@{
        PDF=@{Path=(Join-Path $fixtures 'synthetic.pdf'); Signature='PDF'; MediaType='application/pdf'}
        DOCX=@{Path=(Join-Path $fixtures 'synthetic.docx'); Signature='ZIP_CONTAINER'; MediaType='application/vnd.openxmlformats-officedocument.wordprocessingml.document'}
        XLSX=@{Path=(Join-Path $fixtures 'synthetic.xlsx'); Signature='ZIP_CONTAINER'; MediaType='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}
        JPG=@{Path=(Join-Path $fixtures 'synthetic.jpg'); Signature='JPEG'; MediaType='image/jpeg'}
        PNG=@{Path=(Join-Path $fixtures 'synthetic.png'); Signature='PNG'; MediaType='image/png'}
        ZIP=@{Path=(Join-Path $fixtures 'synthetic.zip'); Signature='ZIP_CONTAINER'; MediaType='application/zip'}
    }

    if ($Mode -eq 'Probe') {
        foreach ($auth in @(@{Name='missing';Token=$null},@{Name='invalid';Token=$invalid})) {
            $authResponse = Send-Json -Method 'POST' -Url "$aiBase/api/review-m0/cases" -Token $auth.Token -Body @{title='probe-auth';processingMode='LOCAL_ONLY'} -Key "probe-auth-$($auth.Name)-$ResourceSuffix"
            Assert-Equal 401 $authResponse.Status "p1.auth.$($auth.Name)"
        }

        $pdfUpload = Send-Multipart -Url "$fileBase/api/files/owned" -Token $tokenA -Path $formats.PDF.Path
        Assert-Equal 201 $pdfUpload.Status 'p2.upload.pdf'
        $pdfMetadata = $pdfUpload.Body | ConvertFrom-Json
        $zipUpload = Send-Multipart -Url "$fileBase/api/files/owned" -Token $tokenA -Path $formats.ZIP.Path
        Assert-Equal 201 $zipUpload.Status 'p3.upload.zip'
        $zipMetadata = $zipUpload.Body | ConvertFrom-Json

        $probeCase = Send-Json -Method 'POST' -Url "$aiBase/api/review-m0/cases" -Token $tokenA -Body @{title='R4 B probe';processingMode='LOCAL_ONLY'} -Key "probe-case-$ResourceSuffix"
        Assert-Equal 201 $probeCase.Status 'p1.reviewCase'
        $probeCaseBody = $probeCase.Body | ConvertFrom-Json
        Assert-Equal 'LOCAL_ONLY' $probeCaseBody.processingMode 'p1.localOnly'
        $caseId = $probeCaseBody.caseId

        $pdfAttach = Send-Json -Method 'POST' -Url "$aiBase/api/review-m0/cases/$caseId/submission-sources" -Token $tokenA -Body @{fileId=$pdfMetadata.fileId;submissionFileName='probe-original.pdf'} -Key "probe-attach-pdf-$ResourceSuffix"
        Assert-Equal 201 $pdfAttach.Status 'p2.attach.pdf'
        $pdfMaterial = @(($pdfAttach.Body | ConvertFrom-Json).materials)[0]
        $pdfUrl = "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)/download"
        $pdfDownload = Send-Json -Method 'GET' -Url $pdfUrl -Token $tokenA -Body $null -Key $null
        Assert-Equal 200 $pdfDownload.Status 'p2.download.status'
        Assert-True (-not $pdfDownload.ReadError) 'p2.download.noReadError'
        Assert-Equal ([IO.FileInfo]$formats.PDF.Path).Length $pdfDownload.Bytes.Length 'p2.download.length'
        Assert-Equal $pdfDownload.Bytes.Length ([long]$pdfDownload.Headers['Content-Length']) 'p2.download.contentLength'
        Assert-IntegrityHeaders -Response $pdfDownload -Name 'p2.download.integrityHeaders'
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$pdfDownload.Bytes,[byte[]][IO.File]::ReadAllBytes($formats.PDF.Path))) 'p2.download.bytes'
        $pdfCrossOwner = Send-Json -Method 'GET' -Url $pdfUrl -Token $tokenB -Body $null -Key $null
        Assert-Equal 404 $pdfCrossOwner.Status 'p2.crossOwner'
        Assert-NoSuccessIntegrityHeaders -Response $pdfCrossOwner -Name 'p2.crossOwner'

        $zipAttach = Send-Json -Method 'POST' -Url "$aiBase/api/review-m0/cases/$caseId/submission-sources" -Token $tokenA -Body @{fileId=$zipMetadata.fileId;submissionFileName=$null} -Key "probe-attach-zip-$ResourceSuffix"
        Assert-Equal 201 $zipAttach.Status 'p3.attach.zip'
        $zipSource = $zipAttach.Body | ConvertFrom-Json
        Assert-Equal 'ZIP' $zipSource.formalFileType 'p3.topLevel.zip'
        $selected = @($zipSource.materials | Where-Object submissionFileName -eq '1-selected.pdf')
        Assert-Equal 1 $selected.Count 'p3.selected.unique'
        Assert-True (-not [string]::IsNullOrWhiteSpace($selected[0].materialId)) 'p3.selected.materialId'
        Assert-True ($null -ne $selected[0].entryLocator -and -not [string]::IsNullOrWhiteSpace($selected[0].entryLocator.entryPath)) 'p3.selected.stableLocator'
        $zipUrl = "$aiBase/api/review-m0/cases/$caseId/materials/$($selected[0].materialId)/download"
        $zipDownload = Send-Json -Method 'GET' -Url $zipUrl -Token $tokenA -Body $null -Key $null
        $selectedBytes = [IO.File]::ReadAllBytes((Join-Path $fixtures 'zip-selected.pdf'))
        Assert-Equal 200 $zipDownload.Status 'p3.download.status'
        Assert-True (-not $zipDownload.ReadError) 'p3.download.noReadError'
        Assert-Equal $selectedBytes.Length $zipDownload.Bytes.Length 'p3.download.length'
        Assert-Equal $zipDownload.Bytes.Length ([long]$zipDownload.Headers['Content-Length']) 'p3.download.contentLength'
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$zipDownload.Bytes,[byte[]]$selectedBytes)) 'p3.download.selectedBytes'
        Assert-True (-not [Linq.Enumerable]::SequenceEqual([byte[]]$zipDownload.Bytes,[byte[]][IO.File]::ReadAllBytes((Join-Path $fixtures 'zip-wrong.pdf')))) 'p3.download.notWrongLeaf'
        Assert-True (-not [Linq.Enumerable]::SequenceEqual([byte[]]$zipDownload.Bytes,[byte[]][IO.File]::ReadAllBytes($formats.ZIP.Path))) 'p3.download.notWholeZip'
        Assert-IntegrityHeaders -Response $zipDownload -Name 'p3.download.integrityHeaders'
        $zipResponseDigest = [string]$zipDownload.Headers['X-Content-SHA256']
        Assert-True ($zipResponseDigest -cne (Get-Sha256Hex ([IO.File]::ReadAllBytes((Join-Path $fixtures 'zip-wrong.pdf'))))) 'p3.download.digestNotWrongLeaf'
        Assert-True ($zipResponseDigest -cne (Get-Sha256Hex ([IO.File]::ReadAllBytes($formats.ZIP.Path)))) 'p3.download.digestNotWholeZip'

        $factsBefore = Invoke-Mysql "SELECT CONCAT(original_file_name,'|',file_size,'|',sha256,'|',storage_version,'|',bucket_name,'|',object_key) FROM file_metadata WHERE file_id='$($pdfMetadata.fileId)'" -Database
        $objectsBefore = (Invoke-Sdk -Action 'count-objects' -FileId $null -Fixture $null).bytes
        $originalDigest = Invoke-Mysql "SELECT sha256 FROM file_metadata WHERE file_id='$($pdfMetadata.fileId)'" -Database
        Invoke-Mysql "UPDATE file_metadata SET sha256=REPEAT('0',64) WHERE file_id='$($pdfMetadata.fileId)'" -Database | Out-Null
        $pdfFixtureBytes = [IO.File]::ReadAllBytes($formats.PDF.Path)
        Assert-FailClosedUrl -Url $pdfUrl -Token $tokenA -Name 'p4.digestMismatch' -ForbiddenBytes $pdfFixtureBytes
        Assert-Equal 0 (Get-StagingPayloadCount -Staging $staging) 'p4.digestMismatch.payloads'
        Invoke-Mysql "UPDATE file_metadata SET sha256='$originalDigest' WHERE file_id='$($pdfMetadata.fileId)'" -Database | Out-Null
        $digestRecovery = Send-Json -Method 'GET' -Url $pdfUrl -Token $tokenA -Body $null -Key $null
        Assert-Equal 200 $digestRecovery.Status 'p4.digestRecovery'
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$digestRecovery.Bytes,[byte[]]$pdfFixtureBytes)) 'p4.digestRecovery.bytes'
        Assert-IntegrityHeaders -Response $digestRecovery -Name 'p4.digestRecovery.integrityHeaders'
        Invoke-Sdk -Action 'replace-equal' -FileId $pdfMetadata.fileId -Fixture $formats.PDF.Path | Out-Null
        Assert-FailClosedUrl -Url $pdfUrl -Token $tokenA -Name 'p4.equalTamper' -ForbiddenBytes $pdfFixtureBytes
        Assert-Equal 0 (Get-StagingPayloadCount -Staging $staging) 'p4.equalTamper.payloads'
        Invoke-Sdk -Action 'restore' -FileId $pdfMetadata.fileId -Fixture $formats.PDF.Path | Out-Null
        $tamperRecovery = Send-Json -Method 'GET' -Url $pdfUrl -Token $tokenA -Body $null -Key $null
        Assert-Equal 200 $tamperRecovery.Status 'p4.tamperRecovery'
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$tamperRecovery.Bytes,[byte[]]$pdfFixtureBytes)) 'p4.tamperRecovery.bytes'
        Assert-IntegrityHeaders -Response $tamperRecovery -Name 'p4.tamperRecovery.integrityHeaders'

        $unicodeName = '奖学金材料（最终）.pdf'
        $renamed = Send-Json -Method 'PATCH' -Url "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)" -Token $tokenA -Body @{expectedVersion=$pdfMaterial.version;submissionFileName=$unicodeName} -Key "probe-rename-$ResourceSuffix"
        Assert-Equal 200 $renamed.Status 'p5.rename'
        foreach ($bad in @("bad`r`nname.pdf",'../bad.pdf')) {
            $rejected = Send-Json -Method 'PATCH' -Url "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)" -Token $tokenA -Body @{expectedVersion=($renamed.Body|ConvertFrom-Json).version;submissionFileName=$bad} -Key ("probe-bad-"+[Guid]::NewGuid().ToString('N'))
            Assert-Equal 400 $rejected.Status 'p5.unsafeNameRejected'
        }
        $renamedDownload = Send-Json -Method 'GET' -Url $pdfUrl -Token $tokenA -Body $null -Key $null
        Assert-Equal 200 $renamedDownload.Status 'p5.download'
        Assert-SafeContentDisposition -Response $renamedDownload -ExpectedName $unicodeName -Name 'p5.contentDisposition'
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$renamedDownload.Bytes,[byte[]]$pdfFixtureBytes)) 'p5.bytesUnchanged'
        Assert-IntegrityHeaders -Response $renamedDownload -Name 'p5.integrityHeaders'
        Assert-True ([string]$renamedDownload.Headers['X-Content-SHA256'] -ceq [string]$pdfDownload.Headers['X-Content-SHA256']) 'p5.integrityHeadersUnchanged'
        Assert-Equal $factsBefore (Invoke-Mysql "SELECT CONCAT(original_file_name,'|',file_size,'|',sha256,'|',storage_version,'|',bucket_name,'|',object_key) FROM file_metadata WHERE file_id='$($pdfMetadata.fileId)'" -Database) 'p5.fileFactsUnchanged'
        Assert-Equal $objectsBefore (Invoke-Sdk -Action 'count-objects' -FileId $null -Fixture $null).bytes 'p5.objectCountUnchanged'

        Stop-TaskProcess -Process $fileProcess
        Assert-FailClosedUrl -Url $pdfUrl -Token $tokenA -Name 'p6.fileDown.review503' -ForbiddenBytes $pdfFixtureBytes
        $fileProcess = Start-Jar -Jar (Join-Path $root 'file-service\target\file-service-1.0.0.jar') -Port $filePort -Label 'file-2' -ExtraArgs $fileArgs
        Assert-True (Wait-Http -Url "$fileBase/v3/api-docs" -Seconds 120) 'p6.fileRecovery.ready'
        $fileRecovery = Send-Json -Method 'GET' -Url $pdfUrl -Token $tokenA -Body $null -Key $null
        Assert-Equal 200 $fileRecovery.Status 'p6.fileRecovery.review200'
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$fileRecovery.Bytes,[byte[]]$pdfFixtureBytes)) 'p6.fileRecovery.bytes'
        Assert-IntegrityHeaders -Response $fileRecovery -Name 'p6.fileRecovery.integrityHeaders'
        & docker stop $minioName | Out-Null
        Assert-FailClosed -Base $fileBase -Token $tokenA -FileId $pdfMetadata.fileId -Name 'p6.minioDown.content' -ForbiddenBytes $pdfFixtureBytes
        Assert-FailClosedUrl -Url $pdfUrl -Token $tokenA -Name 'p6.minioDown.review' -ForbiddenBytes $pdfFixtureBytes
        & docker start $minioName | Out-Null
        Wait-Minio -Port $minioPort
        $minioRecovery = Send-Json -Method 'GET' -Url $pdfUrl -Token $tokenA -Body $null -Key $null
        Assert-Equal 200 $minioRecovery.Status 'p6.minioRecovery.review200'
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$minioRecovery.Bytes,[byte[]]$pdfFixtureBytes)) 'p6.minioRecovery.bytes'
        Assert-IntegrityHeaders -Response $minioRecovery -Name 'p6.minioRecovery.integrityHeaders'
        Assert-Equal 0 (Get-StagingPayloadCount -Staging $staging) 'p6.stagingPayloads'

        Stop-TaskProcess -Process $aiProcess
        Stop-TaskProcess -Process $fileProcess
        Start-Sleep -Milliseconds 500
        Assert-LogSafety -Directory $evidence
        $allLogs = (@(Get-ChildItem -LiteralPath $evidence -Recurse -File -Filter '*.log' | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n")
        Assert-Equal 0 ([regex]::Matches($allLogs,'(?i)(remote model|mcp).{0,80}(success|succeeded|200 OK)')).Count 'p7.remoteSuccess'
        Assert-Equal 0 (Get-StagingPayloadCount -Staging $staging) 'p7.finalPayloads'
        $probeMysqlVersion = Invoke-Mysql 'SELECT VERSION()'
        $probeMysqlImage = (& docker image inspect mysql:8.0 --format '{{index .RepoDigests 0}}' | Out-String).Trim()
        $probeMinioImage = (& docker image inspect minio/minio:RELEASE.2025-09-07T16-13-09Z --format '{{index .RepoDigests 0}}' | Out-String).Trim()
        $result = [ordered]@{status='B_PROBE_PASSED';assertions=$assertions;startedAt=$startedAt.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');productCommit='1da749fb22d8c61917376964088c10b23c834af2';resourcePrefix=$prefix;ports=@($ports);services='mysql/minio/file/ai';reviewSchema='v3';mysqlVersion=$probeMysqlVersion;mysqlImage=$probeMysqlImage;minioRelease='RELEASE.2025-09-07T16-13-09Z';minioImage=$probeMinioImage;localOnly=$true;remoteModelSuccess=0;remoteMcpSuccess=0;stagingPayloads=0}
        $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
        $result | ConvertTo-Json -Depth 6
        return
    }

    $owned = [ordered]@{}
    foreach ($format in $formats.Keys) {
        $upload = Send-Multipart "$fileBase/api/files/owned" $tokenA $formats[$format].Path
        Assert-Equal 201 $upload.Status "b1.upload.$format"
        $metadata = $upload.Body | ConvertFrom-Json
        Assert-True (-not [string]::IsNullOrWhiteSpace($metadata.fileId)) "b1.fileId.$format"
        Assert-Equal ([IO.Path]::GetFileName($formats[$format].Path)) $metadata.originalFileName "b3.name.$format"
        Assert-Equal $formats[$format].MediaType $metadata.declaredContentType "b3.mediaType.$format"
        Assert-Equal ([IO.FileInfo]$formats[$format].Path).Length $metadata.sizeBytes "b3.size.$format"
        Assert-Equal $formats[$format].Signature $metadata.contentSignature "b3.signature.$format"
        Assert-True ($metadata.contentDigest -match '^[0-9a-f]{64}$') "b3.digest.$format"
        Assert-True (-not [string]::IsNullOrWhiteSpace($metadata.storageVersion)) "b3.version.$format"
        $owned[$format] = $metadata
    }
    Assert-Equal $formats.Count (Invoke-Mysql 'SELECT COUNT(*) FROM file_metadata WHERE owner_id=''85001''' -Database) 'b1.ownerA.formats'
    $bUpload = Send-Multipart "$fileBase/api/files/owned" $tokenB (Join-Path $fixtures 'synthetic.pdf')
    Assert-Equal 201 $bUpload.Status 'b1.ownerB.upload'
    $bId = ($bUpload.Body | ConvertFrom-Json).fileId
    Assert-Equal 1 (Invoke-Mysql 'SELECT COUNT(*) FROM file_metadata WHERE owner_id=''85002''' -Database) 'b1.ownerB.binding'
    $spoof = Send-Multipart "$fileBase/api/files/owned" $tokenA (Join-Path $fixtures 'synthetic.pdf') @{'X-User-Id'='85002'}
    Assert-Equal 201 $spoof.Status 'b1.spoof.upload'
    $spoofId = ($spoof.Body | ConvertFrom-Json).fileId
    Assert-Equal 1 (Invoke-Mysql "SELECT COUNT(*) FROM file_metadata WHERE file_id='$spoofId' AND owner_id='85001'" -Database) 'b1.spoof.boundA'
    $authBodies = @()
    foreach ($auth in @(@{Name='missing';Token=$null},@{Name='invalid';Token=$invalid},@{Name='expired';Token=$expired})) {
        $response = Send-Multipart "$fileBase/api/files/owned" $auth.Token (Join-Path $fixtures 'synthetic.pdf')
        Assert-Equal 401 $response.Status "b1.auth.$($auth.Name)"
        $authBodies += $response.Body
    }
    Assert-Equal 1 @($authBodies | Select-Object -Unique).Count 'b1.auth.uniform'
    $headerOnly = Send-Json 'GET' "$fileBase/api/files/owned/$($owned.PDF.fileId)/metadata" $null $null $null @{'X-User-Id'='85001'}
    Assert-Equal 401 $headerOnly.Status 'b1.headerOnly'

    $pdfId = $owned.PDF.fileId
    $ownMetadata = Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/metadata" $tokenA $null $null
    Assert-Equal 200 $ownMetadata.Status 'b2.own.metadata'
    $ownContent = Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/content" $tokenA $null $null
    Assert-Equal 200 $ownContent.Status 'b2.own.content'
    Assert-True (-not $ownContent.ReadError) 'b2.own.noReadError'
    Assert-Equal $ownContent.Bytes.Length ([long]$ownContent.Headers['Content-Length']) 'b2.own.contentLength'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$ownContent.Bytes,[byte[]][IO.File]::ReadAllBytes($formats.PDF.Path))) 'b2.own.bytes'
    Assert-IntegrityHeaders -Response $ownContent -Name 'b3.successHeaders'
    foreach ($suffix in @('metadata','content')) {
        Assert-Equal 404 (Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/$suffix" $tokenB $null $null).Status "b2.cross.$suffix"
        Assert-Equal 404 (Send-Json 'GET' "$fileBase/api/files/owned/00000000-0000-0000-0000-000000000000/$suffix" $tokenA $null $null).Status "b2.missing.$suffix"
    }
    Invoke-Mysql "INSERT INTO file_metadata(id,file_id,file_name,original_file_name,file_path,file_type,file_size,owner_id,sha256,storage_version,content_signature,storage_type,bucket_name,object_key,status) VALUES('r4b-null-id','r4b-null-file','legacy.pdf','legacy.pdf','legacy','application/pdf',1,NULL,NULL,NULL,NULL,'minio','omnidoc','00000000-0000-0000-0000-000000000001.pdf','ACTIVE')" -Database | Out-Null
    $nullMetadata = Send-Json 'GET' "$fileBase/api/files/owned/r4b-null-file/metadata" $tokenA $null $null
    $nullContent = Send-Json 'GET' "$fileBase/api/files/owned/r4b-null-file/content" $tokenA $null $null
    Assert-Equal 404 $nullMetadata.Status 'b2.ownerNull.metadata'
    Assert-Equal 404 $nullContent.Status 'b2.ownerNull.content'
    Assert-Equal $nullMetadata.Body $nullContent.Body 'b2.hidden404.uniformOwnerNull'

    foreach ($format in $formats.Keys) {
        $sdk = Invoke-Sdk 'verify' $owned[$format].fileId $formats[$format].Path
        Assert-Equal 'PASS' $sdk.status "b3.sdk.$format"
        $content = Send-Json 'GET' "$fileBase/api/files/owned/$($owned[$format].fileId)/content" $tokenA $null $null
        Assert-Equal 200 $content.Status "b3.http.$format"
        Assert-True (-not $content.ReadError) "b3.http.$format.noReadError"
        Assert-Equal $content.Bytes.Length ([long]$content.Headers['Content-Length']) "b3.http.$format.contentLength"
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$content.Bytes,[byte[]][IO.File]::ReadAllBytes($formats[$format].Path))) "b3.bytes.$format"
        Assert-IntegrityHeaders -Response $content -Name "b3.http.$format.integrityHeaders"
    }
    $expectedOwnerALocators = $formats.Count + 1
    Assert-Equal $expectedOwnerALocators (Invoke-Mysql 'SELECT COUNT(DISTINCT object_key) FROM file_metadata WHERE owner_id=''85001'' AND original_file_name IN (''synthetic.pdf'',''synthetic.docx'',''synthetic.xlsx'',''synthetic.jpg'',''synthetic.png'',''synthetic.zip'')' -Database) 'b3.locators.uniqueIncludingSpoofUpload'
    Assert-Equal 0 (Invoke-Mysql 'SELECT COUNT(*) FROM file_metadata WHERE owner_id IS NOT NULL AND (object_key IS NULL OR object_key='''' OR bucket_name IS NULL OR bucket_name='''')' -Database) 'b3.locators.nonempty'
    $coreObjectCount = (Invoke-Sdk -Action 'count-objects' -FileId $null -Fixture $null).bytes
    Assert-Equal ($formats.Count + 2) $coreObjectCount 'b3.objectCountFromRunUploads'

    $pdfFixtureBytes = [IO.File]::ReadAllBytes($formats.PDF.Path)
    $originalDigest = (Invoke-Mysql "SELECT sha256 FROM file_metadata WHERE file_id='$pdfId'" -Database)
    Invoke-Mysql "UPDATE file_metadata SET sha256=REPEAT('0',64) WHERE file_id='$pdfId'" -Database | Out-Null
    Assert-FailClosed $fileBase $tokenA $pdfId 'b3.digestMismatch' $pdfFixtureBytes
    Assert-Equal 0 (Get-StagingPayloadCount $staging) 'b3.digestMismatch.payloads'
    Invoke-Mysql "UPDATE file_metadata SET sha256='$originalDigest' WHERE file_id='$pdfId'" -Database | Out-Null
    foreach ($action in @('replace-equal','replace-short','replace-long')) {
        Invoke-Sdk $action $pdfId $formats.PDF.Path | Out-Null
        Assert-FailClosed $fileBase $tokenA $pdfId "b3.$action" $pdfFixtureBytes
        Assert-Equal 0 (Get-StagingPayloadCount $staging) "b3.$action.payloads"
        Invoke-Sdk 'restore' $pdfId $formats.PDF.Path | Out-Null
        $integrityRecovery = Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/content" $tokenA $null $null
        Assert-Equal 200 $integrityRecovery.Status "b3.$action.recovery"
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$integrityRecovery.Bytes,[byte[]]$pdfFixtureBytes)) "b3.$action.recovery.bytes"
        Assert-IntegrityHeaders -Response $integrityRecovery -Name "b3.$action.recovery.integrityHeaders"
    }
    & docker stop $minioName | Out-Null
    Assert-FailClosed $fileBase $tokenA $pdfId 'b3.objectOpenException' $pdfFixtureBytes
    & docker start $minioName | Out-Null; Wait-Minio $minioPort
    $objectOpenRecovery = Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/content" $tokenA $null $null
    Assert-Equal 200 $objectOpenRecovery.Status 'b3.objectOpenRecovery'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$objectOpenRecovery.Bytes,[byte[]]$pdfFixtureBytes)) 'b3.objectOpenRecovery.bytes'
    Assert-IntegrityHeaders -Response $objectOpenRecovery -Name 'b3.objectOpenRecovery.integrityHeaders'
    Assert-Equal 0 (Get-StagingPayloadCount $staging) 'b3.integrityFailurePayloads'

    $abortUpload = Send-Multipart "$fileBase/api/files/owned" $tokenA (Join-Path $fixtures 'abort.pdf')
    Assert-Equal 201 $abortUpload.Status 'b3.abort.upload'
    $abortId = ($abortUpload.Body | ConvertFrom-Json).fileId
    $abortRequest = New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::Get, "$fileBase/api/files/owned/$abortId/content")
    $abortRequest.Headers.TryAddWithoutValidation('Authorization',"Bearer $tokenA") | Out-Null
    $abortResponse = $client.SendAsync($abortRequest,[Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    $abortStream = $abortResponse.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $abortBuffer = New-Object byte[] 1024
    Assert-True (($abortStream.Read($abortBuffer,0,$abortBuffer.Length)) -gt 0) 'b3.abort.readPrefix'
    $abortStream.Dispose(); $abortResponse.Dispose(); $abortRequest.Dispose(); Start-Sleep -Milliseconds 500
    Assert-Equal 0 (Get-StagingPayloadCount $staging) 'b3.abort.payloads'

    $caseCreate = Send-Json 'POST' "$aiBase/api/review-m0/cases" $tokenA @{title='R4 B formal';processingMode='LOCAL_ONLY'} 'r4b-formal-case'
    Assert-Equal 201 $caseCreate.Status 'b4.case'
    $caseId = ($caseCreate.Body | ConvertFrom-Json).caseId
    $reviewSources = [ordered]@{}
    foreach ($format in $formats.Keys) {
        $logical = if ($format -eq 'ZIP') { $null } else { "review-$($format.ToLowerInvariant()).$($format.ToLowerInvariant())" }
        if ($format -eq 'JPG') { $logical='review-jpg.jpg' }
        $attach = Send-Json 'POST' "$aiBase/api/review-m0/cases/$caseId/submission-sources" $tokenA @{fileId=$owned[$format].fileId;submissionFileName=$logical} "r4b-attach-$format"
        Assert-Equal 201 $attach.Status "b4.attach.$format"
        $source = $attach.Body | ConvertFrom-Json
        Assert-Equal $format $source.formalFileType "b4.type.$format"
        $expectedMaterialCount = if ($format -eq 'ZIP') { 6 } else { 1 }
        Assert-Equal $expectedMaterialCount @($source.materials).Count "b4.materialCount.$format"
        $reviewSources[$format] = $source
    }
    $pdfMaterial = @($reviewSources.PDF.materials)[0]
    $zipMaterial = @($reviewSources.ZIP.materials | Where-Object submissionFileName -eq '1-selected.pdf')[0]
    Assert-True ($null -ne $zipMaterial.entryLocator -and -not [string]::IsNullOrWhiteSpace($zipMaterial.entryLocator.entryPath)) 'b4.zipStableLocator'
    $ordinaryReviewDownloads = [ordered]@{}
    foreach ($format in @('PDF','DOCX','XLSX','JPG','PNG')) {
        $material = @($reviewSources[$format].materials)[0]
        $download = Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId/materials/$($material.materialId)/download" $tokenA $null $null
        Assert-Equal 200 $download.Status "b4.download.$format.status"
        Assert-True (-not $download.ReadError) "b4.download.$format.noReadError"
        Assert-Equal $download.Bytes.Length ([long]$download.Headers['Content-Length']) "b4.download.$format.contentLength"
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$download.Bytes,[byte[]][IO.File]::ReadAllBytes($formats[$format].Path))) "b4.download.$format.bytes"
        Assert-IntegrityHeaders -Response $download -Name "b4.download.$format.integrityHeaders"
        $ordinaryReviewDownloads[$format] = $download
    }
    $reviewPdf = $ordinaryReviewDownloads.PDF
    $reviewZip = Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId/materials/$($zipMaterial.materialId)/download" $tokenA $null $null
    Assert-Equal 200 $reviewZip.Status 'b4.download.zipLeaf'
    Assert-True (-not $reviewZip.ReadError) 'b4.download.zipLeafNoReadError'
    Assert-Equal $reviewZip.Bytes.Length ([long]$reviewZip.Headers['Content-Length']) 'b4.download.zipLeafContentLength'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$reviewZip.Bytes,[byte[]][IO.File]::ReadAllBytes((Join-Path $fixtures 'zip-selected.pdf')))) 'b4.download.zipLeafBytes'
    Assert-True (-not [Linq.Enumerable]::SequenceEqual([byte[]]$reviewZip.Bytes,[byte[]][IO.File]::ReadAllBytes((Join-Path $fixtures 'zip-wrong.pdf')))) 'b4.download.zipLeafNotWrongLeaf'
    Assert-True (-not [Linq.Enumerable]::SequenceEqual([byte[]]$reviewZip.Bytes,[byte[]][IO.File]::ReadAllBytes($formats.ZIP.Path))) 'b4.download.zipLeafNotWholeZip'
    Assert-IntegrityHeaders -Response $reviewZip -Name 'b4.download.zipLeaf.integrityHeaders'
    $zipExpected = @{
        '0-wrong.pdf'=(Join-Path $fixtures 'zip-wrong.pdf'); '1-selected.pdf'=(Join-Path $fixtures 'zip-selected.pdf');
        '2-document.docx'=$formats.DOCX.Path; '3-workbook.xlsx'=$formats.XLSX.Path;
        '4-photo.jpg'=$formats.JPG.Path; '5-image.png'=$formats.PNG.Path
    }
    foreach ($material in @($reviewSources.ZIP.materials)) {
        Assert-True ($zipExpected.ContainsKey($material.submissionFileName)) 'b4.zipLeaf.expectedName'
        $leafDownload = Send-Json -Method 'GET' -Url "$aiBase/api/review-m0/cases/$caseId/materials/$($material.materialId)/download" -Token $tokenA -Body $null -Key $null
        Assert-Equal 200 $leafDownload.Status "b4.zipLeaf.$($material.formalFileType).status"
        Assert-True (-not $leafDownload.ReadError) "b4.zipLeaf.$($material.formalFileType).noReadError"
        Assert-Equal ([IO.FileInfo]($zipExpected[$material.submissionFileName])).Length $leafDownload.Bytes.Length "b4.zipLeaf.$($material.formalFileType).length"
        Assert-Equal $leafDownload.Bytes.Length ([long]$leafDownload.Headers['Content-Length']) "b4.zipLeaf.$($material.formalFileType).contentLength"
        Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$leafDownload.Bytes,[byte[]][IO.File]::ReadAllBytes($zipExpected[$material.submissionFileName]))) "b4.zipLeaf.$($material.formalFileType).bytes"
        Assert-IntegrityHeaders -Response $leafDownload -Name "b4.zipLeaf.$($material.formalFileType).integrityHeaders"
    }
    $reviewCrossOwner = Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)/download" $tokenB $null $null
    Assert-Equal 404 $reviewCrossOwner.Status 'b4.crossOwner'
    Assert-NoSuccessIntegrityHeaders -Response $reviewCrossOwner -Name 'b4.crossOwner'
    $reviewZipCrossOwner = Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId/materials/$($zipMaterial.materialId)/download" $tokenB $null $null
    Assert-Equal 404 $reviewZipCrossOwner.Status 'b4.zipCrossOwner'
    Assert-NoSuccessIntegrityHeaders -Response $reviewZipCrossOwner -Name 'b4.zipCrossOwner'
    $reviewMissing = Send-Json 'GET' "$aiBase/api/review-m0/cases/00000000-0000-0000-0000-000000000000/materials/00000000-0000-0000-0000-000000000000/download" $tokenA $null $null
    Assert-Equal 404 $reviewMissing.Status 'b4.missing'
    Assert-NoSuccessIntegrityHeaders -Response $reviewMissing -Name 'b4.missing'

    $factsBefore = Invoke-Mysql "SELECT CONCAT(original_file_name,'|',file_type,'|',file_size,'|',sha256,'|',storage_version,'|',content_signature,'|',bucket_name,'|',object_key) FROM file_metadata WHERE file_id='$pdfId'" -Database
    $objectsBefore = (Invoke-Sdk 'count-objects' $null $null).bytes
    foreach ($bad in @("bad`r`nname.pdf",'../bad.pdf','folder/bad.pdf','folder\bad.pdf','.','..',("bad$([char]0)name.pdf"),("bad$([char]1)name.pdf"))) {
        $reject = Send-Json 'PATCH' "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)" $tokenA @{expectedVersion=$pdfMaterial.version;submissionFileName=$bad} ('r4b-bad-name-'+[Guid]::NewGuid().ToString('N'))
        Assert-Equal 400 $reject.Status 'b5.unsafeNameRejected'
    }
    $logicalName = '奖学金 "最终";副本.pdf'
    $rename = Send-Json 'PATCH' "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)" $tokenA @{expectedVersion=$pdfMaterial.version;submissionFileName=$logicalName} 'r4b-rename-unicode'
    Assert-Equal 200 $rename.Status 'b5.rename'
    Assert-Equal $logicalName (($rename.Body|ConvertFrom-Json).submissionFileName) 'b5.renamePreserved'
    $logicalDownload = Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)/download" $tokenA $null $null
    Assert-Equal 200 $logicalDownload.Status 'b5.download'
    Assert-SafeContentDisposition -Response $logicalDownload -ExpectedName $logicalName -Name 'b5.contentDisposition'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$logicalDownload.Bytes,[byte[]][IO.File]::ReadAllBytes($formats.PDF.Path))) 'b5.bytes'
    Assert-IntegrityHeaders -Response $logicalDownload -Name 'b5.integrityHeaders'
    Assert-True ([string]$logicalDownload.Headers['X-Content-SHA256'] -ceq [string]$reviewPdf.Headers['X-Content-SHA256']) 'b5.integrityHeadersUnchanged'
    Assert-Equal $factsBefore (Invoke-Mysql "SELECT CONCAT(original_file_name,'|',file_type,'|',file_size,'|',sha256,'|',storage_version,'|',content_signature,'|',bucket_name,'|',object_key) FROM file_metadata WHERE file_id='$pdfId'" -Database) 'b5.fileFactsUnchanged'
    Assert-Equal $objectsBefore (Invoke-Sdk 'count-objects' $null $null).bytes 'b5.singleObject'
    Assert-Equal 'PDF' ((Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId/materials" $tokenA $null $null).Body|ConvertFrom-Json).items.Where({$_.materialId -eq $pdfMaterial.materialId})[0].formalFileType 'b5.typeAfterRename'

    $legacyMetadata = Send-Json 'GET' "$fileBase/api/files/metadata/$pdfId" $null $null $null
    $legacyDownload = Send-Json 'GET' "$fileBase/api/files/download/$pdfId" $null $null $null
    Assert-Equal 404 $legacyMetadata.Status 'b2.legacyOwnedMetadataHidden'
    Assert-True ($legacyDownload.Status -ne 200) 'b2.legacyOwnedDownloadHidden'
    Assert-True (-not [Linq.Enumerable]::SequenceEqual([byte[]]$legacyDownload.Bytes,[byte[]][IO.File]::ReadAllBytes($formats.PDF.Path))) 'b2.legacyNoBytes'
    Assert-Equal 200 (Send-Json 'GET' "$fileBase/api/files/metadata/r4b-null-file" $null $null $null).Status 'b2.legacyOwnerNullRiskRecorded'

    Stop-TaskProcess $fileProcess
    $reviewPdfUrl = "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)/download"
    Assert-FailClosedUrl -Url $reviewPdfUrl -Token $tokenA -Name 'b6.fileDown.review503' -ForbiddenBytes $pdfFixtureBytes
    $fileProcess = Start-Jar (Join-Path $root 'file-service\target\file-service-1.0.0.jar') $filePort 'file-2' $fileArgs
    Assert-True (Wait-Http "$fileBase/v3/api-docs" 120) 'b6.fileRestart.ready'
    $fileRestartContent = Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/content" $tokenA $null $null
    Assert-Equal 200 $fileRestartContent.Status 'b6.fileRestart.content'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$fileRestartContent.Bytes,[byte[]]$pdfFixtureBytes)) 'b6.fileRestart.bytes'
    Assert-IntegrityHeaders -Response $fileRestartContent -Name 'b6.fileRestart.integrityHeaders'
    Assert-Equal 404 (Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/content" $tokenB $null $null).Status 'b6.fileRestart.owner'
    $reviewAfterFileRestart = Send-Json 'GET' $reviewPdfUrl $tokenA $null $null
    Assert-Equal 200 $reviewAfterFileRestart.Status 'b6.fileRestart.review'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$reviewAfterFileRestart.Bytes,[byte[]]$pdfFixtureBytes)) 'b6.fileRestart.reviewBytes'
    Assert-IntegrityHeaders -Response $reviewAfterFileRestart -Name 'b6.fileRestart.reviewIntegrityHeaders'

    & docker stop $minioName | Out-Null
    Assert-Equal 200 (Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/metadata" $tokenA $null $null).Status 'b6.minioDown.metadata'
    Assert-FailClosed -Base $fileBase -Token $tokenA -FileId $pdfId -Name 'b6.minioDown.content' -ForbiddenBytes $pdfFixtureBytes
    Assert-Equal 503 (Send-Multipart "$fileBase/api/files/owned" $tokenA (Join-Path $fixtures 'synthetic.pdf')).Status 'b6.minioDown.upload'
    Assert-FailClosedUrl -Url $reviewPdfUrl -Token $tokenA -Name 'b6.minioDown.review' -ForbiddenBytes $pdfFixtureBytes
    & docker start $minioName | Out-Null; Wait-Minio $minioPort
    $reviewAfterMinioRestart = Send-Json 'GET' $reviewPdfUrl $tokenA $null $null
    Assert-Equal 200 $reviewAfterMinioRestart.Status 'b6.minioRecovery.review'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$reviewAfterMinioRestart.Bytes,[byte[]]$pdfFixtureBytes)) 'b6.minioRecovery.bytes'
    Assert-IntegrityHeaders -Response $reviewAfterMinioRestart -Name 'b6.minioRecovery.integrityHeaders'

    foreach ($auth in @($invalid,$expired)) { Assert-Equal 401 (Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId" $auth $null $null).Status 'b6.reviewAuth401' }
    & docker restart $mysqlName | Out-Null; Wait-Mysql
    Assert-Equal 200 (Send-Json 'GET' "$fileBase/api/files/owned/$pdfId/metadata" $tokenA $null $null).Status 'b6.mysqlRestart.file'
    Assert-Equal 200 (Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId" $tokenA $null $null).Status 'b6.mysqlRestart.review'
    Assert-Equal $logicalName (((Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId/materials" $tokenA $null $null).Body|ConvertFrom-Json).items.Where({$_.materialId -eq $pdfMaterial.materialId})[0].submissionFileName) 'b6.mysqlRestart.name'
    $reviewAfterMysqlRestart = Send-Json 'GET' $reviewPdfUrl $tokenA $null $null
    Assert-Equal 200 $reviewAfterMysqlRestart.Status 'b6.mysqlRestart.download'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$reviewAfterMysqlRestart.Bytes,[byte[]]$pdfFixtureBytes)) 'b6.mysqlRestart.bytes'
    Assert-IntegrityHeaders -Response $reviewAfterMysqlRestart -Name 'b6.mysqlRestart.integrityHeaders'
    Stop-TaskProcess $aiProcess
    $aiProcess = Start-Jar (Join-Path $root 'ai-service\target\ai-service-1.0.0.jar') $aiPort 'ai-2' $aiArgs
    Assert-True (Wait-Http "$aiBase/api-docs" 150) 'b6.aiRestart.ready'
    $afterRestartDownload = Send-Json 'GET' "$aiBase/api/review-m0/cases/$caseId/materials/$($pdfMaterial.materialId)/download" $tokenA $null $null
    Assert-Equal 200 $afterRestartDownload.Status 'b6.aiRestart.download'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$afterRestartDownload.Bytes,[byte[]][IO.File]::ReadAllBytes($formats.PDF.Path))) 'b6.aiRestart.bytes'
    Assert-IntegrityHeaders -Response $afterRestartDownload -Name 'b6.aiRestart.integrityHeaders'
    Assert-SafeContentDisposition -Response $afterRestartDownload -ExpectedName $logicalName -Name 'b6.aiRestart.contentDisposition'
    Assert-Equal $objectsBefore (Invoke-Sdk 'count-objects' $null $null).bytes 'b6.objectCount'
    Assert-Equal 0 (Get-StagingPayloadCount $staging) 'b6.payloads'
    Assert-Equal 0 (Invoke-Mysql 'SELECT COUNT(*) FROM file_metadata WHERE owner_id IS NOT NULL AND (sha256 IS NULL OR storage_version IS NULL OR content_signature IS NULL)' -Database) 'b6.noHalfMetadata'

    Stop-TaskProcess $aiProcess; Stop-TaskProcess $fileProcess
    Assert-LogSafety $evidence
    $allLogs = (@(Get-ChildItem -LiteralPath $evidence -Recurse -File -Filter '*.log' | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n")
    Assert-Equal 0 ([regex]::Matches($allLogs,'(?i)(remote model|mcp).{0,80}(success|succeeded|200 OK)')).Count 'b7.remoteSuccess'
    Assert-Equal 0 (Get-StagingPayloadCount $staging) 'b7.finalPayloads'
    $mysqlVersion = (Invoke-Mysql 'SELECT VERSION()')
    $mysqlImage = (& docker image inspect mysql:8.0 --format '{{index .RepoDigests 0}}' | Out-String).Trim()
    $minioImage = (& docker image inspect minio/minio:RELEASE.2025-09-07T16-13-09Z --format '{{index .RepoDigests 0}}' | Out-String).Trim()
    $result = [ordered]@{
        status='NATIVE_B_PASS'; assertions=$assertions; startedAt=$startedAt.ToString('o'); finishedAt=[DateTimeOffset]::Now.ToString('o')
        productCommit='1da749fb22d8c61917376964088c10b23c834af2'; resourcePrefix=$prefix; ports=@($ports); mysqlVersion=$mysqlVersion; mysqlImage=$mysqlImage; minioRelease='RELEASE.2025-09-07T16-13-09Z'; minioImage=$minioImage
        b1='PASS'; b2='PASS'; b3='PASS'; b4='PASS'; b5='PASS'; b6='PASS'; b7='PASS'; uploads=@{ownerA=($formats.Count+2);ownerB=1;formalSix=$formats.Count}; review=@{topLevel=$formats.Count;zipLeaves=$reviewSources.ZIP.materials.Count}; localOnly=$true; remoteModelSuccess=0; remoteMcpSuccess=0; stagingPayloads=0
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
    $result | ConvertTo-Json -Depth 8
} finally {
    Stop-TaskProcess -Process $aiProcess
    Stop-TaskProcess -Process $fileProcess
    foreach ($process in $processes) { Stop-TaskProcess -Process $process }
    $client.Dispose(); $clientHandler.Dispose()
    foreach ($name in @('SPRING_PROFILES_ACTIVE','MYSQL_LOCAL_HOST','MYSQL_LOCAL_PORT','MYSQL_LOCAL_USERNAME','MYSQL_LOCAL_PASSWORD','MYSQL_HOST','MYSQL_PORT','MYSQL_USERNAME','MYSQL_PASSWORD','MYSQL_DATABASE','MINIO_LOCAL_ENDPOINT','MINIO_ENDPOINT','MINIO_ACCESS_KEY','MINIO_SECRET_KEY','OMNIDOC_JWT_SECRET','DASHSCOPE_API_KEY','OPENAI_API_KEY','RABBITMQ_USERNAME','RABBITMQ_PASSWORD','REDIS_LOCAL_HOST','REDIS_PORT','R4B_JDBC_URL','R4B_DB_USER','R4B_DB_PASSWORD','R4B_MINIO_ENDPOINT','R4B_MINIO_ACCESS','R4B_MINIO_SECRET','R4B_MINIO_BUCKET')) { Remove-Item "Env:\$name" -ErrorAction SilentlyContinue }
    foreach ($name in $oldProxy.Keys) { [Environment]::SetEnvironmentVariable($name,$oldProxy[$name],'Process') }
    $old = $ErrorActionPreference; $ErrorActionPreference='SilentlyContinue'
    & docker rm --force $mysqlName $minioName 2>$null | Out-Null
    & docker volume rm $mysqlVolume $minioVolume 2>$null | Out-Null
    & docker network rm $networkName 2>$null | Out-Null
    $ErrorActionPreference=$old
    $cleanupFailures = New-Object 'System.Collections.Generic.List[string]'
    foreach ($containerName in @($mysqlName,$minioName)) {
        if (@(& docker ps --all --filter "name=^/$containerName$" --format '{{.Names}}' 2>$null).Count -ne 0) { $cleanupFailures.Add('container') }
    }
    foreach ($volumeName in @($mysqlVolume,$minioVolume)) {
        if (@(& docker volume ls --filter "name=^$volumeName$" --format '{{.Name}}' 2>$null).Count -ne 0) { $cleanupFailures.Add('volume') }
    }
    $networkResidue = @(& docker network ls --filter "name=^$networkName$" --format '{{.Name}}' 2>$null)
    if ($networkResidue.Count -ne 0) { $cleanupFailures.Add('network') }
    foreach ($process in $processes) { if (-not $process.HasExited) { $cleanupFailures.Add('process') } }
    if ($cleanupFailures.Count -ne 0) { throw "RUNNER_CLEANUP_FAILED $($cleanupFailures -join ',')" }
}
