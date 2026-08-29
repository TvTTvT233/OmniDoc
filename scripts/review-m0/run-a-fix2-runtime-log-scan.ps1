param(
    [Parameter(Mandatory = $true)]
    [string[]]$LogPath,
    [switch]$RequireDiagnostic
)

$ErrorActionPreference = 'Stop'

$resolvedFiles = @()
foreach ($candidate in $LogPath) {
    $resolved = Resolve-Path -LiteralPath $candidate
    $item = Get-Item -LiteralPath $resolved.Path
    if ($item.PSIsContainer) {
        $resolvedFiles += Get-ChildItem -LiteralPath $item.FullName -File -Recurse
    } else {
        $resolvedFiles += $item
    }
}

$lines = @($resolvedFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName })

function Count-Matches([string]$Pattern) {
    return @($lines | Select-String -Pattern $Pattern).Count
}

$counts = [ordered]@{
    files = @($resolvedFiles).Count
    lines = $lines.Count
    sql = Count-Matches '(?i)\b(?:select\s+.+\s+from|insert\s+into|update\s+[A-Za-z0-9_`]+\s+set|delete\s+from|create\s+table|alter\s+table|drop\s+table|merge\s+into)\b'
    javaStackFrame = Count-Matches '^\s+at\s+(?:[A-Za-z0-9_.$/]+)\('
    throwableHeader = Count-Matches '(?i)^(?:Caused by:\s+)?[A-Za-z0-9_.$]+(?:Exception|Error)(?::|\s*$)'
    connectionUrl = Count-Matches '(?i)\b(?:jdbc:(?:mysql|h2):|redis(?:s)?://|amqp(?:s)?://|mysql://)'
    credential = Count-Matches '(?i)(?:password|secret(?:-key)?|access-key|api-key)\s*[=:]|authorization\s*:\s*bearer'
    jwt = Count-Matches '\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b'
    objectKey = Count-Matches '(?i)\bobjectKey\b'
    hashOrDigest = Count-Matches '(?i)\b(?:hash|payloadDigest|contentDigest|fileDigest|entryDigest)\s*[=:]'
    body = Count-Matches '(?i)\b(?:requestBody|responseBody|body)\s*[=:]'
    generatedPassword = Count-Matches '(?i)using generated security password'
    diagnostic = Count-Matches 'event=[A-Z0-9_.:-]+ reasonCode=[A-Z0-9_.:-]+ requestId=[A-Za-z0-9_.:-]+ correlationId=[A-Za-z0-9_.:-]+ exceptionCategory=[A-Z_]+ retryable=(?:true|false) retryCount=\d+ count=\d+'
}

$forbidden = @(
    'sql', 'javaStackFrame', 'throwableHeader', 'connectionUrl', 'credential', 'jwt',
    'objectKey', 'hashOrDigest', 'body', 'generatedPassword'
)
$failed = @($forbidden | Where-Object { $counts[$_] -ne 0 })
if ($RequireDiagnostic -and $counts.diagnostic -eq 0) {
    $failed += 'diagnostic'
}

[pscustomobject]@{
    status = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' }
    counts = [pscustomobject]$counts
    failed = @($failed)
} | ConvertTo-Json -Depth 4

if ($failed.Count -ne 0) {
    exit 1
}
