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
        Name = 'Tavily key-shaped literal'
        Pattern = '(?i)\btvly-(?:dev-)?[A-Za-z0-9_-]{20,}\b'
    },
    [pscustomobject]@{
        Name = 'Credential in URL query'
        Pattern = '(?i)(?:\?|&)(?:tavilyApiKey|api[_-]?key)=[A-Za-z0-9._~-]{12,}'
    },
    [pscustomobject]@{
        Name = 'Literal bearer credential'
        Pattern = '(?i)Authorization\s*[:=,]\s*["'']?Bearer\s+[A-Za-z0-9._~+/=-]{16,}'
    },
    [pscustomobject]@{
        Name = 'Private key material'
        Pattern = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
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

    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $scanned++
    foreach ($rule in $rules) {
        foreach ($match in [regex]::Matches($text, $rule.Pattern)) {
            $line = 1 + ([regex]::Matches($text.Substring(0, $match.Index), "`n")).Count
            $findings.Add([pscustomobject]@{
                File = $relativeFile
                Line = $line
                Rule = $rule.Name
            })
        }
    }
}

if ($findings.Count -gt 0) {
    [Console]::Error.WriteLine("T04 secret leakage check failed with $($findings.Count) finding(s). Values are intentionally redacted.")
    $findings | Sort-Object File, Line, Rule | Format-Table -AutoSize
    exit 1
}

Write-Host "[PASS] T04 secret leakage static check scanned $scanned text files; no key-shaped Tavily token, URL credential, literal bearer credential, or private-key marker was found."
