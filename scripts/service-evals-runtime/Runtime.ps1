[CmdletBinding()]
param(
    [ValidateSet('Initialize','Start','RestartAi','Stop')][string]$Action='Start',
    [string]$JavaPath,
    [string]$ProofPath,
    [string]$ArtifactRoot
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if(-not $ArtifactRoot){$ArtifactRoot=$repo}
$ArtifactRoot=(Resolve-Path -LiteralPath $ArtifactRoot).Path
$runtime=Join-Path $repo '.local/service-runtime'
$envFile=Join-Path $runtime 'runtime.env'
$manifestPath=Join-Path $runtime 'processes.json'
$compose=Join-Path $PSScriptRoot 'compose.yml'
$ports=@{'gateway-service'=18080;'user-service'=18081;'file-service'=18082;'ai-service'=18083;'document-service'=18084}
function New-Secret { [guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N') }
function Fixture-Digest([string]$RootPath){
    $source=Join-Path $RootPath 'ai-service/target/test-classes/com/javaee/aiservice/consistency'
    $classes=@(Get-ChildItem -LiteralPath $source -Filter 'ServiceFlowEvaluationApplication*.class'|Sort-Object Name)
    if($classes.Count -lt 2){throw 'Compile the explicit service-flow test entry first.'}
    $text=($classes|ForEach-Object {$_.Name+':'+(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}) -join "`n"
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()
}
function Import-LocalEnvironment {
    foreach($line in Get-Content -LiteralPath $envFile){
        if($line -match '^([A-Z_0-9]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
    }
    foreach($item in Get-ChildItem Env:){
        if($item.Name -match '^(DASHSCOPE|OPENAI|TAVILY|SPRING_AI|AI_MODELS)'){
            [Environment]::SetEnvironmentVariable($item.Name,'','Process')
        }
    }
}
function Port-Open([int]$Port){
    $client=[Net.Sockets.TcpClient]::new()
    try { return $client.ConnectAsync('127.0.0.1',$Port).Wait(400) -and $client.Connected }
    catch { return $false } finally { $client.Dispose() }
}
function Read-Manifest {
    if(Test-Path -LiteralPath $manifestPath){ return @(Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json) }
    return @()
}
function Stop-Owned($entry){
    $process=Get-Process -Id $entry.processId -ErrorAction SilentlyContinue
    if(-not $process){return}
    if($process.StartTime.ToUniversalTime().Ticks -ne ([DateTimeOffset]$entry.startedAtUtc).UtcTicks){throw 'Process identity changed; refusing to stop.'}
    if([IO.Path]::GetFullPath($process.Path) -ne [IO.Path]::GetFullPath($entry.javaPath)){throw 'Process executable changed; refusing to stop.'}
    Stop-Process -Id $process.Id -ErrorAction Stop
    $process.WaitForExit(20000)|Out-Null
    if(-not $process.HasExited){throw 'Owned process did not stop.'}
}
if($Action -eq 'Initialize'){
    New-Item -ItemType Directory -Path $runtime -Force|Out-Null
    if(-not(Test-Path -LiteralPath $envFile)){
        $mysql=New-Secret;$redis=New-Secret;$mq=New-Secret;$minio=New-Secret
        @("MYSQL_ROOT_PASSWORD=$mysql",'MYSQL_DATABASE=omnidoc','MYSQL_LOCAL_HOST=127.0.0.1',
          'MYSQL_LOCAL_PORT=23306','MYSQL_LOCAL_USERNAME=root',"MYSQL_LOCAL_PASSWORD=$mysql",
          'REDIS_LOCAL_HOST=127.0.0.1',"REDIS_PASSWORD=$redis", "REDIS_LOCAL_PASSWORD=$redis",'REDIS_PORT=26379','SPRING_DATA_REDIS_PORT=26379',
          'RABBITMQ_HOST=127.0.0.1','RABBITMQ_USERNAME=evaluator',"RABBITMQ_PASSWORD=$mq",'SPRING_RABBITMQ_PORT=25673',
          'MINIO_LOCAL_ENDPOINT=http://127.0.0.1:29000','MINIO_ACCESS_KEY=evaluator',"MINIO_SECRET_KEY=$minio",
          "OMNIDOC_JWT_SECRET=$(New-Secret)","OMNIDOC_INDEX_INTERNAL_TOKEN=$(New-Secret)","OMNIDOC_REVIEW_INTERNAL_TOKEN=$(New-Secret)",
          'AI_MEMORY_SCHEMA_AUTO_MIGRATION_ENABLED=true')|Set-Content -LiteralPath $envFile -Encoding utf8
    }
    # Separate compose project, volumes and ports; never reuse the existing baseline database.
    & docker compose --env-file $envFile -f $compose up -d --wait --wait-timeout 180 mysql redis rabbitmq minio
    if($LASTEXITCODE -ne 0){throw 'Isolated dependencies failed to start.'}
    Write-Output 'Isolated dependencies ready; local credentials were not displayed.'
    return
}
if($Action -eq 'Stop'){
    foreach($entry in Read-Manifest){Stop-Owned $entry}
    Write-Output 'Owned application processes stopped; dependencies and evidence preserved.'
    return
}
if(-not(Test-Path -LiteralPath $envFile)){throw 'Initialize the isolated runtime first.'}
Import-LocalEnvironment
$entries=@(Read-Manifest)
$old=$null
if($Action -eq 'RestartAi'){
    $old=@($entries|Where-Object service -eq 'ai-service')
    if($old.Count -ne 1){throw 'Exactly one owned AI process is required.'}
    $old=$old[0];$JavaPath=$old.javaPath;$ArtifactRoot=$old.artifactRoot
    if(-not $ProofPath){throw 'A proof output path is required.'}
    $resolvedProof=[IO.Path]::GetFullPath($ProofPath)
    $allowedPrefix=[IO.Path]::GetFullPath((Join-Path $repo '.local'))+[IO.Path]::DirectorySeparatorChar
    if(-not $resolvedProof.StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Proof must be stored under this checkout .local.'}
    $currentHash=(Get-FileHash -LiteralPath $old.jar -Algorithm SHA256).Hash.ToLowerInvariant()
    if($currentHash -ne $old.artifactSha256){throw 'AI artifact changed; restart proof would be invalid.'}
    if((Fixture-Digest $ArtifactRoot) -ne $old.fixtureSha256){throw 'Test fixture changed; restart proof would be invalid.'}
    Stop-Owned $old
    $services=@('ai-service')
}else{$services=@('user-service','file-service','document-service','ai-service','gateway-service')}
if(-not $JavaPath -or -not(Test-Path -LiteralPath $JavaPath)){throw 'Explicit Java 17 executable required.'}
if((& $JavaPath -version 2>&1|Out-String) -notmatch 'version "17\.'){throw 'Java 17 required.'}
foreach($service in $services){
    if(Port-Open $ports[$service]){throw "Port $($ports[$service]) is occupied; refusing to replace a service."}
    $jar=Join-Path $ArtifactRoot "$service/target/$service-1.0.0.jar"
    if(-not(Test-Path -LiteralPath $jar)){throw "Build artifact missing for $service"}
    $arguments=@('-Xms128m','-Xmx640m')
    $fixtureSha=$null
    if($service -eq 'ai-service'){
        $fixtureSha=Fixture-Digest $ArtifactRoot
        $fixtureDir=Join-Path $runtime ('embedding-fixture-'+$fixtureSha)
        $fixturePackage=Join-Path $fixtureDir 'com/javaee/aiservice/consistency'
        New-Item -ItemType Directory -Force -Path $fixturePackage|Out-Null
        $source=Join-Path $ArtifactRoot 'ai-service/target/test-classes/com/javaee/aiservice/consistency'
        $classes=@(Get-ChildItem -LiteralPath $source -Filter 'ServiceFlowEvaluationApplication*.class')
        if($classes.Count -lt 2){throw 'Compile the explicit service-flow test entry first.'}
        foreach($file in $classes){Copy-Item -LiteralPath $file.FullName -Destination $fixturePackage -Force}
        $support=Join-Path $env:USERPROFILE '.m2/repository/org/springframework/boot/spring-boot-test/3.2.0/spring-boot-test-3.2.0.jar'
        if(-not(Test-Path -LiteralPath $support)){throw 'Spring Boot test annotation support is missing.'}
        $arguments+=@('-Dloader.main=com.javaee.aiservice.consistency.ServiceFlowEvaluationApplication',
            ('-Dloader.path='+$fixtureDir.Replace('\','/')+','+$support.Replace('\','/')),
            '-cp',$jar,'org.springframework.boot.loader.launch.PropertiesLauncher')
    }else{$arguments+=@('-jar',$jar)}
    $arguments+=@('--spring.profiles.active=local','--server.address=127.0.0.1',"--server.port=$($ports[$service])",
        '--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl',
        '--logging.level.com.javaee=INFO','--spring.data.redis.port=26379','--spring.rabbitmq.port=25673')
    if($service -eq 'document-service'){
        $arguments+=@('--file.service.url=http://127.0.0.1:18082','--omnidoc.index.embedding-model=synthetic-embedding-v1','--omnidoc.index.embedding-dimension=16')
    }
    if($service -eq 'ai-service'){
        $arguments+=@(('--spring.config.additional-location='+((Join-Path $repo 'scripts/baseline/no-external-models.yml').Replace('\','/'))),
            '--spring.ai.dashscope.embedding.enabled=false','--document.service.url=http://127.0.0.1:18084',
            '--omnidoc.index.worker-enabled=true','--omnidoc.index.poll-ms=500','--spring.data.redis.database=11','--ai.vector.mode=baseline',
            '--ai.vector.dimension=16','--ai.vector.embedding.model=synthetic-embedding-v1',
            '--ai.review-m0.persistence.enabled=true','--ai.review-m0.persistence.schema.auto-migration.enabled=true',
            '--ai.review-m0.runtime.file-service-base-uri=http://127.0.0.1:18082','--ai.aiops.scheduling.enabled=false',
            '--ai.agent.recovery.enabled=true','--ai.agent.recovery.schema-auto-migration=true')
    }
    if($service -eq 'gateway-service'){
        $arguments+=@('--gateway.route.user-uri=http://127.0.0.1:18081','--gateway.route.file-uri=http://127.0.0.1:18082',
            '--gateway.route.document-uri=http://127.0.0.1:18084','--gateway.route.ai-uri=http://127.0.0.1:18083','--gateway.route.ai-ws-uri=ws://127.0.0.1:18083')
    }
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmssfff'
    $argumentFile=Join-Path $runtime "$service-$stamp.args"
    $arguments|ForEach-Object {'"'+$_.Replace('\','/').Replace('"','\"')+'"'}|Set-Content -LiteralPath $argumentFile -Encoding utf8NoBOM
    $out=Join-Path $runtime "$service-$stamp.private.log";$err=Join-Path $runtime "$service-$stamp.private.err"
    $p=Start-Process -FilePath $JavaPath -ArgumentList ('@"'+$argumentFile+'"') -WorkingDirectory $ArtifactRoot -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    $entry=[pscustomobject]@{service=$service;processId=$p.Id;startedAtUtc=$p.StartTime.ToUniversalTime().ToString('o');javaPath=$JavaPath;artifactRoot=$ArtifactRoot;jar=$jar;artifactSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant();fixtureSha256=$fixtureSha;port=$ports[$service];stdout=$out;stderr=$err}
    $entries=@($entries|Where-Object service -ne $service)+@($entry)
    $entries|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Output "Launched isolated $service; readiness pending."
}
foreach($service in $services){
    $deadline=[DateTime]::UtcNow.AddSeconds(180)
    while(-not(Port-Open $ports[$service])){
        $entry=@($entries|Where-Object service -eq $service)[0]
        if(-not(Get-Process -Id $entry.processId -ErrorAction SilentlyContinue)){throw "Isolated $service exited before readiness."}
        if([DateTime]::UtcNow -ge $deadline){throw "Isolated $service readiness timed out."}
        Start-Sleep -Milliseconds 300
    }
}
if($Action -eq 'RestartAi'){
    $new=@($entries|Where-Object service -eq 'ai-service')[0]
    if($old.fixtureSha256 -ne $new.fixtureSha256){throw 'Test fixture changed during restart.'}
    [ordered]@{beforePid=$old.processId;afterPid=$new.processId;artifactSha256=$new.artifactSha256}|ConvertTo-Json|Set-Content -LiteralPath $resolvedProof -Encoding utf8
}
