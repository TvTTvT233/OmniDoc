[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$ReportPath
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

$secretRules = @(
    [pscustomobject]@{ Name = 'Provider key-shaped literal'; Pattern = '(?i)\b(?:qdrant|dashscope|tavily|openai)[_-]?(?:api[_-]?)?key[ \t]*[:=][ \t]*["'']?[A-Za-z0-9._~+/=-]{20,}' },
    [pscustomobject]@{ Name = 'OpenAI-style secret literal'; Pattern = '(?i)\bsk-[A-Za-z0-9_-]{20,}\b' },
    [pscustomobject]@{ Name = 'Literal bearer credential'; Pattern = '(?i)Authorization\s*[:=,]\s*["'']?Bearer\s+[A-Za-z0-9._~+/=-]{16,}' },
    [pscustomobject]@{ Name = 'Credential embedded in URL'; Pattern = '(?i)https?://[^/\s:@]+:[^/\s@]{8,}@' },
    [pscustomobject]@{ Name = 'Private key material'; Pattern = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----' },
    [pscustomobject]@{ Name = 'AWS access key literal'; Pattern = '\bAKIA[0-9A-Z]{16}\b' }
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
    foreach ($rule in $secretRules) {
        foreach ($match in [regex]::Matches($text, $rule.Pattern)) {
            $line = 1 + ([regex]::Matches($text.Substring(0, $match.Index), "`n")).Count
            $findings.Add([pscustomobject]@{ File = $relativeFile; Line = $line; Rule = $rule.Name })
        }
    }
}

$fixtureRelative = 'ai-service/src/test/resources/memory/memory-v1-fixture.json'
$fixturePath = Join-Path $RepositoryRoot $fixtureRelative
if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
    $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = 'Memory V1 fixture missing' })
} else {
    $fixtureText = Get-Content -LiteralPath $fixturePath -Raw -Encoding utf8
    $fixture = $fixtureText | ConvertFrom-Json
    if ($fixture.synthetic -ne $true -or $fixture.remoteModel -ne $false -or $fixture.remoteEmbedding -ne $false) {
        $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = 'Fixture is outside the synthetic local-only boundary' })
    }
    if (-not $fixture.conversation.userId.StartsWith('memory-v1-user-') -or
            -not $fixture.conversation.conversationId.StartsWith('memory-v1-conv-')) {
        $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = 'Fixture identity is outside the Memory V1 namespace' })
    }

    $contentSamples = [System.Collections.Generic.List[string]]::new()
    foreach ($message in $fixture.messages) {
        $contentSamples.Add([string]$message.content)
    }
    foreach ($section in @('goal', 'constraints', 'facts', 'decisions', 'openQuestions', 'todos', 'resources')) {
        $value = $fixture.expectedSummary.$section
        if ($null -eq $value) { continue }
        if ($value -is [System.Array]) {
            foreach ($itemValue in $value) { $contentSamples.Add([string]$itemValue.content) }
        } elseif ($null -ne $value.content) {
            $contentSamples.Add([string]$value.content)
        }
    }
    foreach ($entry in $fixture.runtimeState.PSObject.Properties) {
        $contentSamples.Add([string]$entry.Value)
    }
    $piiRules = @(
        [pscustomobject]@{ Name = 'Email-like PII in Memory fixture'; Pattern = '\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b' },
        [pscustomobject]@{ Name = 'Mainland phone-like PII in Memory fixture'; Pattern = '(?<!\d)1[3-9]\d{9}(?!\d)' },
        [pscustomobject]@{ Name = 'Mainland ID-like PII in Memory fixture'; Pattern = '(?<!\d)\d{17}[0-9Xx](?!\d)' }
    )
    foreach ($sample in $contentSamples) {
        foreach ($rule in $piiRules) {
            if ([regex]::IsMatch($sample, $rule.Pattern)) {
                $findings.Add([pscustomobject]@{ File = $fixtureRelative; Line = 0; Rule = $rule.Name })
            }
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
        if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) {
            $findings.Add([pscustomobject]@{ File = $ReportPath; Line = 0; Rule = 'Acceptance report missing' })
        } else {
            $reportText = Get-Content -LiteralPath $ReportPath -Raw -Encoding utf8
            $report = $reportText | ConvertFrom-Json
            $forbiddenReportFields = @('messages', 'conversation', 'summary', 'prompt', 'document', 'toolResults', 'selectedText')
            foreach ($field in $forbiddenReportFields) {
                if ($report.PSObject.Properties.Name -contains $field) {
                    $findings.Add([pscustomobject]@{ File = $ReportPath; Line = 0; Rule = "Forbidden report field: $field" })
                }
            }
            foreach ($sample in $contentSamples) {
                if (-not [string]::IsNullOrWhiteSpace($sample) -and $reportText.Contains($sample)) {
                    $findings.Add([pscustomobject]@{ File = $ReportPath; Line = 0; Rule = 'Synthetic conversation/runtime content copied into report' })
                }
            }
            foreach ($rule in $secretRules) {
                if ([regex]::IsMatch($reportText, $rule.Pattern)) {
                    $findings.Add([pscustomobject]@{ File = $ReportPath; Line = 0; Rule = "Report $($rule.Name)" })
                }
            }
            foreach ($flag in $report.contentSafety.PSObject.Properties) {
                if ($flag.Value -ne $false) {
                    $findings.Add([pscustomobject]@{ File = $ReportPath; Line = 0; Rule = "Report content-safety flag is not false: $($flag.Name)" })
                }
            }
        }
    }
}

if ($findings.Count -gt 0) {
    [Console]::Error.WriteLine("Memory V1 sensitive-data check failed with $($findings.Count) finding(s). Values are intentionally redacted.")
    $findings | Sort-Object File, Line, Rule | Format-Table -AutoSize
    exit 1
}

Write-Host "[PASS] Memory V1 sensitive-data check scanned $scanned text files and verified the synthetic fixture/report content boundary."
