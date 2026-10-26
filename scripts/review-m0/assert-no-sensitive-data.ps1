[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$ReportPath,
    [string]$EvidencePath,
    [string[]]$StaticScanRoots = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..\..'
}
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$repositoryPrefix = $RepositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
    [System.IO.Path]::DirectorySeparatorChar
$fixturePath = Join-Path $RepositoryRoot 'ai-service\src\test\resources\review-m0\acceptance\review-m0-p0-fixtures.json'
$findings = [System.Collections.Generic.List[object]]::new()

function Add-Finding {
    param([string]$File, [int]$Line, [string]$Rule)
    $findings.Add([pscustomobject]@{ File = $File; Line = $Line; Rule = $Rule })
}

function Test-ForbiddenJsonProperties {
    param([object]$Node, [string]$FileLabel)
    if ($null -eq $Node) { return }
    if ($Node -is [System.Management.Automation.PSCustomObject]) {
        foreach ($property in $Node.PSObject.Properties) {
            if ($property.Name -in @(
                    'original', 'objectKey', 'contentHash', 'ocrText', 'documentContent',
                    'credential', 'previewText', 'payloadText', 'responseText', 'prompt')) {
                Add-Finding -File $FileLabel -Line 0 -Rule "Forbidden JSON property: $($property.Name)"
            }
            Test-ForbiddenJsonProperties -Node $property.Value -FileLabel $FileLabel
        }
        return
    }
    if ($Node -is [System.Collections.IEnumerable] -and $Node -isnot [string]) {
        foreach ($item in $Node) { Test-ForbiddenJsonProperties -Node $item -FileLabel $FileLabel }
    }
}

function Test-JsonContentBoundary {
    param([string]$Path, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Add-Finding -File $Label -Line 0 -Rule 'JSON evidence artifact missing'
        return
    }
    $text = Get-Content -LiteralPath $Path -Raw -Encoding utf8
    $parsed = $text | ConvertFrom-Json
    Test-ForbiddenJsonProperties -Node $parsed -FileLabel $Label
    foreach ($rule in $secretRules) {
        if ([regex]::IsMatch($text, $rule.Pattern)) {
            Add-Finding -File $Label -Line 0 -Rule "JSON $($rule.Name)"
        }
    }
    foreach ($rule in $piiRules) {
        if ([regex]::IsMatch($text, $rule.Pattern)) {
            Add-Finding -File $Label -Line 0 -Rule "JSON $($rule.Name)"
        }
    }
}

function Resolve-SafeRelativeRoot {
    param([string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or
            [System.IO.Path]::IsPathRooted($RelativePath) -or
            $RelativePath -split '[\\/]' -contains '..') {
        throw "Static scan root must be a repository-relative path without traversal: $RelativePath"
    }
    $absolute = [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot $RelativePath))
    $prefix = $RepositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
        [System.IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Static scan root escapes the repository: $RelativePath"
    }
    if (-not (Test-Path -LiteralPath $absolute)) {
        throw "Static scan root does not exist: $RelativePath"
    }
    return $absolute
}

$secretRules = @(
    [pscustomobject]@{ Name = 'Provider key-shaped literal'; Pattern = '(?i)\b(?:dashscope|tavily|openai|qdrant)[_-]?(?:api[_-]?)?key[ \t]*[:=][ \t]*["'']?[A-Za-z0-9._~+/=-]{20,}' },
    [pscustomobject]@{ Name = 'OpenAI-style secret literal'; Pattern = '(?i)\bsk-[A-Za-z0-9_-]{20,}\b' },
    [pscustomobject]@{ Name = 'Bearer credential literal'; Pattern = '(?i)Authorization\s*[:=,]\s*["'']?Bearer\s+[A-Za-z0-9._~+/=-]{16,}' },
    [pscustomobject]@{ Name = 'Credential embedded in URL'; Pattern = '(?i)https?://[^/\s:@]+:[^/\s@]{8,}@' },
    [pscustomobject]@{ Name = 'Private key material'; Pattern = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----' },
    [pscustomobject]@{ Name = 'AWS access key literal'; Pattern = '\bAKIA[0-9A-Z]{16}\b' }
)
$piiRules = @(
    [pscustomobject]@{ Name = 'Email-like PII'; Pattern = '\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b' },
    [pscustomobject]@{ Name = 'Mainland phone-like PII'; Pattern = '(?<!\d)1[3-9]\d{9}(?!\d)' },
    [pscustomobject]@{ Name = 'Mainland ID-like PII'; Pattern = '(?<!\d)\d{17}[0-9Xx](?!\d)' }
)

$relativeFiles = @(& git -C $RepositoryRoot ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) {
    throw 'git ls-files failed'
}
$files = [System.Collections.Generic.List[string]]::new()
foreach ($relative in $relativeFiles) {
    if (-not [string]::IsNullOrWhiteSpace($relative)) {
        $files.Add((Join-Path $RepositoryRoot $relative))
    }
}
foreach ($root in $StaticScanRoots) {
    $absoluteRoot = Resolve-SafeRelativeRoot -RelativePath $root
    if (Test-Path -LiteralPath $absoluteRoot -PathType Leaf) {
        $files.Add($absoluteRoot)
    } else {
        Get-ChildItem -LiteralPath $absoluteRoot -Recurse -File | ForEach-Object { $files.Add($_.FullName) }
    }
}
$productionRootPrefixes = @($StaticScanRoots | ForEach-Object {
    (Resolve-SafeRelativeRoot -RelativePath $_).TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
        [System.IO.Path]::DirectorySeparatorChar
})

$scanned = 0
foreach ($absoluteFile in @($files | Sort-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $absoluteFile -PathType Leaf)) { continue }
    $item = Get-Item -LiteralPath $absoluteFile
    if ($item.Length -gt 5MB) { continue }
    $bytes = [System.IO.File]::ReadAllBytes($absoluteFile)
    if ($bytes -contains 0) { continue }
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $normalizedFile = [System.IO.Path]::GetFullPath($absoluteFile)
    if (-not $normalizedFile.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to scan a file outside the repository: $normalizedFile"
    }
    $relative = $normalizedFile.Substring($repositoryPrefix.Length).Replace('\', '/')
    $scanned++
    foreach ($rule in $secretRules) {
        foreach ($match in [regex]::Matches($text, $rule.Pattern)) {
            $line = 1 + ([regex]::Matches($text.Substring(0, $match.Index), "`n")).Count
            Add-Finding -File $relative -Line $line -Rule $rule.Name
        }
    }
    $isProductionScanFile = @($productionRootPrefixes | Where-Object {
        $absoluteFile.StartsWith($_, [System.StringComparison]::OrdinalIgnoreCase) -or
        $absoluteFile.Equals($_.TrimEnd([System.IO.Path]::DirectorySeparatorChar),
            [System.StringComparison]::OrdinalIgnoreCase)
    }).Count -gt 0
    if ($isProductionScanFile) {
        $sensitiveLogPattern = '(?im)\b(?:log|logger)\.(?:trace|debug|info|warn|error)\s*\([^\r\n;]*(?:original|objectKey|contentHash|ocrText|documentContent)\b'
        foreach ($match in [regex]::Matches($text, $sensitiveLogPattern)) {
            $line = 1 + ([regex]::Matches($text.Substring(0, $match.Index), "`n")).Count
            Add-Finding -File $relative -Line $line -Rule 'Sensitive Review M0 value in log statement'
        }
    }
}

if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
    Add-Finding -File 'review-m0-p0-fixtures.json' -Line 0 -Rule 'Fixture missing'
} else {
    $fixtureText = Get-Content -LiteralPath $fixturePath -Raw -Encoding utf8
    $fixture = $fixtureText | ConvertFrom-Json
    if ($fixture.synthetic -ne $true -or $fixture.containsRealUserData -ne $false) {
        Add-Finding -File 'review-m0-p0-fixtures.json' -Line 0 -Rule 'Fixture exceeds synthetic boundary'
    }
    foreach ($rule in $piiRules) {
        if ([regex]::IsMatch($fixtureText, $rule.Pattern)) {
            Add-Finding -File 'review-m0-p0-fixtures.json' -Line 0 -Rule $rule.Name
        }
    }
}

Test-JsonContentBoundary -Path $ReportPath -Label 'review-m0-acceptance-report.json'
Test-JsonContentBoundary -Path $EvidencePath -Label 'review-m0-integrated-evidence.json'

if ($findings.Count -gt 0) {
    [Console]::Error.WriteLine("Review M0 sensitive-data check failed with $($findings.Count) redacted finding(s).")
    $findings | Sort-Object File, Line, Rule | Format-Table -AutoSize
    exit 1
}

Write-Host "[PASS] Review M0 sensitive-data check scanned $scanned text files; no secret or fixture PII pattern was found."
exit 0
