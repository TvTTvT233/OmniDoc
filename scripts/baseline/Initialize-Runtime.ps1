[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runtime = Join-Path $repo '.local/baseline-runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$path = Join-Path $runtime 'runtime.env'
if (Test-Path -LiteralPath $path) { throw "Refusing to overwrite existing runtime configuration: $path" }
function New-Secret { return [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N') }
$mysql = New-Secret
$redis = New-Secret
$mq = New-Secret
$minio = New-Secret
@(
    "MYSQL_ROOT_PASSWORD=$mysql", 'MYSQL_DATABASE=omnidoc', 'MYSQL_LOCAL_HOST=127.0.0.1',
    'MYSQL_LOCAL_PORT=13306', 'MYSQL_LOCAL_USERNAME=root', "MYSQL_LOCAL_PASSWORD=$mysql",
    'REDIS_LOCAL_HOST=127.0.0.1', "REDIS_PASSWORD=$redis", "REDIS_LOCAL_PASSWORD=$redis",
    'SPRING_DATA_REDIS_PORT=16379', 'RABBITMQ_HOST=127.0.0.1', 'RABBITMQ_USERNAME=baseline',
    "RABBITMQ_PASSWORD=$mq", 'SPRING_RABBITMQ_PORT=15673',
    'MINIO_LOCAL_ENDPOINT=http://127.0.0.1:19000', 'MINIO_ACCESS_KEY=baseline', "MINIO_SECRET_KEY=$minio",
    "OMNIDOC_JWT_SECRET=$(New-Secret)", 'SERVER_ADDRESS=127.0.0.1',
    'AI_MEMORY_SCHEMA_AUTO_MIGRATION_ENABLED=true', 'VECTOR_STORE_MODE=baseline'
) | Set-Content -LiteralPath $path -Encoding utf8
Write-Output "Created ignored local configuration: $path (values not displayed)."
