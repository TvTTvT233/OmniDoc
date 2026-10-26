[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JavaPath,
    [ValidateSet('user-service','file-service','document-service','ai-service','gateway-service')]
    [string[]]$Services=@('document-service','ai-service'),
    [ValidateSet('Disabled','Synthetic')][string]$Mode='Disabled',
    [string]$DependencyClasspathFile,
    [switch]$WorkerPaused,
    [int]$EmbeddingDelayMs=0,
    [int]$EmbeddingFailAt=-1,
    [int]$LeaseSeconds=30
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/consistency-evidence'
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
foreach($line in Get-Content (Join-Path $repo '.local/baseline-runtime/runtime.env')) {
    if($line -match '^([A-Za-z_][A-Za-z_0-9]*)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim(),'Process')}
}
if(-not $env:OMNIDOC_INDEX_INTERNAL_TOKEN -or $env:OMNIDOC_INDEX_INTERNAL_TOKEN.Length -lt 32){throw 'Private independent internal token is required'}
if(-not(Test-Path -LiteralPath $JavaPath)){throw 'JDK path missing'}
if((& $JavaPath -version 2>&1|Out-String) -notmatch 'version "17\.'){throw 'JDK17 required'}
[Environment]::SetEnvironmentVariable('DASHSCOPE_API_KEY','','Process')
[Environment]::SetEnvironmentVariable('OPENAI_API_KEY','','Process')
$manifestPath=Join-Path $evidence 'runtime-processes.json'
$entries=@();if(Test-Path $manifestPath){$entries=@(Get-Content $manifestPath -Raw|ConvertFrom-Json)}
$ports=@{'user-service'=8081;'file-service'=8082;'document-service'=8084;'ai-service'=8083;'gateway-service'=8080}
foreach($service in $Services){
    if(Get-NetTCPConnection -State Listen -LocalPort $ports[$service] -ErrorAction SilentlyContinue){throw "Port $($ports[$service]) occupied; refusing to reuse an unknown process"}
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmssfff'
    $stdout=Join-Path $evidence "$service-$Mode-$stamp.out.log"
    $stderr=Join-Path $evidence "$service-$Mode-$stamp.err.log"
    $jar=Join-Path $repo "$service/target/$service-1.0.0.jar"
    $arguments=@('-Xms128m','-Xmx768m')
    if($service -eq 'ai-service' -and $Mode -eq 'Synthetic'){
        if(-not $DependencyClasspathFile -or -not(Test-Path -LiteralPath $DependencyClasspathFile)){throw 'Explicit test dependency classpath file required'}
        $testMain=Join-Path $repo 'ai-service/target/test-classes/com/javaee/aiservice/consistency/SyntheticConsistencyApplication.class'
        if(-not(Test-Path -LiteralPath $testMain)){throw 'Test-only main class has not been compiled'}
        # Export only the explicit synthetic entry point; other unit-test @Configuration classes
        # must not be visible to the application component scan.
        $testRoot=Join-Path $evidence 'synthetic-test-classes'
        $entryDir=Join-Path $testRoot 'com/javaee/aiservice/consistency'
        New-Item -ItemType Directory -Path $entryDir -Force|Out-Null
        Get-ChildItem -LiteralPath (Split-Path $testMain) -Filter 'SyntheticConsistencyApplication*.class'|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $entryDir -Force}
        $dependencies=@((Get-Content -LiteralPath $DependencyClasspathFile -Raw).Trim() -split ';'|Where-Object {$_.Replace('\','/').TrimEnd('/') -notmatch '/target/test-classes$'})
        $classpath=$testRoot+';'+(Join-Path $repo 'ai-service/target/classes')+';'+($dependencies -join ';')
        $arguments+=@('-cp',$classpath,'com.javaee.aiservice.consistency.SyntheticConsistencyApplication')
    }else{
        if(-not(Test-Path -LiteralPath $jar)){throw "Missing built jar: $service"}
        $arguments+=@('-jar',$jar)
    }
    $arguments+=@('--spring.profiles.active=local','--server.address=127.0.0.1','--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl','--logging.level.com.javaee=INFO')
    if($service -eq 'document-service'){
        $arguments+="--omnidoc.index.lease-seconds=$LeaseSeconds"
        if($Mode -eq 'Synthetic'){$arguments+=@('--omnidoc.index.embedding-model=synthetic-embedding-v1','--omnidoc.index.embedding-dimension=16')}
    }
    if($service -eq 'ai-service'){
        $arguments+=@(('--spring.config.additional-location='+((Join-Path $repo 'scripts/baseline/no-external-models.yml').Replace('\','/'))),'--spring.ai.dashscope.embedding.enabled=false','--document.service.url=http://127.0.0.1:8084','--omnidoc.index.poll-ms=1000','--spring.data.redis.database=11','--ai.vector.mode=baseline')
        $arguments+='--omnidoc.index.worker-enabled='+(-not $WorkerPaused).ToString().ToLowerInvariant()
        if($Mode -eq 'Synthetic'){
            $arguments+=@('--ai.vector.dimension=16','--ai.vector.embedding.model=synthetic-embedding-v1',"--synthetic.embedding.delay-ms=$EmbeddingDelayMs","--synthetic.embedding.fail-at=$EmbeddingFailAt")
        }else{
            $arguments+=@('--spring.ai.dashscope.embedding.model=text-embedding-v4','--spring.ai.dashscope.embedding.dimension=1024')
        }
    }
    $argFile=Join-Path $evidence "$service-$Mode-$stamp.args"
    # Java argfile quoting avoids Windows command-line length limits and contains no credentials.
    $arguments|ForEach-Object {'"'+$_.Replace('\','/').Replace('"','\"')+'"'}|Set-Content -LiteralPath $argFile -Encoding utf8NoBOM
    $process=Start-Process -FilePath $JavaPath -ArgumentList ('@"'+$argFile+'"') -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $entries+=[pscustomobject]@{service=$service;mode=$Mode;processId=$process.Id;startedAtUtc=$process.StartTime.ToUniversalTime().ToString('o');port=$ports[$service];stdout=$stdout;stderr=$stderr;argumentFile=$argFile}
    $entries|ConvertTo-Json -Depth 4|Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Output "Launched $service ($Mode) pid=$($process.Id); readiness remains unverified."
}
