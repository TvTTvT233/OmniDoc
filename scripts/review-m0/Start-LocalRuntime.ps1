[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaPath,
    [ValidateSet('file-service','document-service','ai-service','gateway-service','user-service')]
    [string[]]$Services=@('file-service','document-service','ai-service','gateway-service'))
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidence=Join-Path $repo '.local/review-m0-20261027'
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
foreach($line in Get-Content (Join-Path $repo '.local/baseline-runtime/runtime.env')){
    if($line -match '^([A-Za-z_][A-Za-z_0-9]*)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim(),'Process')}
}
foreach($line in Get-Content (Join-Path $evidence 'internal-service.private.env')){
    if($line -match '^([A-Za-z_][A-Za-z_0-9]*)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim(),'Process')}
}
if(-not(Test-Path -LiteralPath $JavaPath)){throw 'Java executable missing'}
if((& $JavaPath -version 2>&1|Out-String) -notmatch 'version "17\.'){throw 'JDK17 required'}
[Environment]::SetEnvironmentVariable('DASHSCOPE_API_KEY','','Process')
[Environment]::SetEnvironmentVariable('OPENAI_API_KEY','','Process')
$manifest=Join-Path $evidence 'runtime-processes.json'
$entries=@();if(Test-Path $manifest){$entries=@(Get-Content $manifest -Raw|ConvertFrom-Json)}
$ports=@{'user-service'=8081;'file-service'=8082;'document-service'=8084;'ai-service'=8083;'gateway-service'=8080}
foreach($service in $Services){
    if(Get-NetTCPConnection -State Listen -LocalPort $ports[$service] -ErrorAction SilentlyContinue){throw "Port $($ports[$service]) occupied"}
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmssfff'
    $jar=Join-Path $repo "$service/target/$service-1.0.0.jar"
    if(-not(Test-Path $jar)){throw "Missing jar: $service"}
    $arguments=@('-Xms128m','-Xmx768m','-jar',$jar,'--spring.profiles.active=local','--server.address=127.0.0.1')
    if($service -eq 'ai-service'){
        $arguments+=@('--ai.review-m0.persistence.enabled=true','--ai.review-m0.persistence.schema.auto-migration.enabled=true','--ai.review-m0.runtime.file-service-base-uri=http://127.0.0.1:8082',('--spring.config.additional-location='+((Join-Path $repo 'scripts/baseline/no-external-models.yml').Replace('\','/'))),'--spring.ai.dashscope.embedding.enabled=false','--document.service.url=http://127.0.0.1:8084','--spring.data.redis.database=11','--ai.vector.mode=baseline','--ai.aiops.scheduling.enabled=false')
    }
    if($service -eq 'document-service'){$arguments+='--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl'}
    $argFile=Join-Path $evidence "$service-$stamp.args"
    $arguments|ForEach-Object {'"'+$_.Replace('\','/').Replace('"','\"')+'"'}|Set-Content -LiteralPath $argFile -Encoding utf8NoBOM
    $stdout=Join-Path $evidence "$service-$stamp.out.log";$stderr=Join-Path $evidence "$service-$stamp.err.log"
    $process=Start-Process -FilePath $JavaPath -ArgumentList ('@"'+$argFile+'"') -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $entries+=[pscustomobject]@{service=$service;mode='Disabled';processId=$process.Id;startedAtUtc=$process.StartTime.ToUniversalTime().ToString('o');port=$ports[$service];jarSha256=(Get-FileHash $jar -Algorithm SHA256).Hash;stdout=$stdout;stderr=$stderr;argumentFile=$argFile}
    $entries|ConvertTo-Json -Depth 5|Set-Content $manifest -Encoding utf8
    Write-Output "Started $service; readiness pending."
}
