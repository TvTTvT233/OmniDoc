[CmdletBinding()]
param(
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..\..'
}
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path

$gitMarker = Join-Path $RepositoryRoot '.git'
if (-not (Test-Path -LiteralPath $gitMarker)) {
    throw "Repository root does not contain .git: $RepositoryRoot"
}

$relativeFiles = @(& git -C $RepositoryRoot ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) {
    throw 'git ls-files failed'
}

$rules = @(
    [pscustomobject]@{
        Name = 'Provider key-shaped literal'
        Pattern = '(?i)\b(?:qdrant|dashscope|tavily|openai)[_-]?(?:api[_-]?)?key[ \t]*[:=][ \t]*["'']?[A-Za-z0-9._~+/=-]{20,}'
    },
    [pscustomobject]@{
        Name = 'OpenAI-style secret literal'
        Pattern = '(?i)\bsk-[A-Za-z0-9_-]{20,}\b'
    },
    [pscustomobject]@{
        Name = 'Literal bearer credential'
        Pattern = '(?i)Authorization\s*[:=,]\s*["'']?Bearer\s+[A-Za-z0-9._~+/=-]{16,}'
    },
    [pscustomobject]@{
        Name = 'Credential embedded in URL'
        Pattern = '(?i)https?://[^/\s:@]+:[^/\s@]{8,}@'
    },
    [pscustomobject]@{
        Name = 'Private key material'
        Pattern = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    },
    [pscustomobject]@{
        Name = 'AWS access key literal'
        Pattern = '\bAKIA[0-9A-Z]{16}\b'
    }
)

$findings = [System.Collections.Generic.List[object]]::new()
$scanned = 0

foreach ($relativeFile in $relativeFiles) {
    if ([string]::IsNullOrWhiteSpace($relativeFile)) {
        continue
    }
    $absoluteFile = Join-Path $RepositoryRoot $relativeFile
    if (-not (Test-Path -LiteralPath $absoluteFile -PathType Leaf)) {
        continue
    }
    $item = Get-Item -LiteralPath $absoluteFile
    if ($item.Length -gt 5MB) {
        continue
    }
    $bytes = [System.IO.File]::ReadAllBytes($absoluteFile)
    if ($bytes -contains 0) {
        continue
    }
    $content = [System.Text.Encoding]::UTF8.GetString($bytes)
    $scanned++
    foreach ($rule in $rules) {
        foreach ($match in [regex]::Matches($content, $rule.Pattern)) {
            $line = 1 + ([regex]::Matches($content.Substring(0, $match.Index), "`n")).Count
            $findings.Add([pscustomobject]@{
                File = $relativeFile
                Line = $line
                Rule = $rule.Name
            })
        }
    }
}

$fixtureRelative = 'ai-service/src/test/resources/qdrant/qv04-vector-fixture.json'
$fixturePath = Join-Path $RepositoryRoot $fixtureRelative
if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
    $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = 'QV04 fixture missing' })
} else {
    $fixtureText = Get-Content -LiteralPath $fixturePath -Raw -Encoding utf8
    $fixture = $fixtureText | ConvertFrom-Json
    if ($fixture.synthetic -ne $true) {
        $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = 'Fixture is not marked synthetic' })
    }
    if ($fixture.remoteEmbedding -ne $false) {
        $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = 'Remote embedding is not disabled' })
    }
    foreach ($scope in $fixture.scopes) {
        if (-not $scope.userId.StartsWith('qv04-user-') -or -not $scope.knowledgeBaseId.StartsWith('qv04-kb-')) {
            $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = 'Fixture ID is outside the synthetic QV04 namespace' })
        }
    }
    $piiRules = @(
        [pscustomobject]@{ Name = 'Email-like PII in Qdrant fixture'; Pattern = '\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b' },
        [pscustomobject]@{ Name = 'Mainland phone-like PII in Qdrant fixture'; Pattern = '(?<!\d)1[3-9]\d{9}(?!\d)' },
        [pscustomobject]@{ Name = 'Mainland ID-like PII in Qdrant fixture'; Pattern = '(?<!\d)\d{17}[0-9Xx](?!\d)' }
    )
    foreach ($rule in $piiRules) {
        if ([regex]::IsMatch($fixtureText, $rule.Pattern)) {
            $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = $rule.Name })
        }
    }
}

if ($findings.Count -gt 0) {
    [Console]::Error.WriteLine("QV04 sensitive-data check failed with $($findings.Count) finding(s). Values are intentionally redacted.")
    $findings | Sort-Object File, Line, Rule | Format-Table -AutoSize
    exit 1
}

Write-Host "[PASS] QV04 sensitive-data check scanned $scanned text files and verified the synthetic, local-only fixture boundary."
