[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..\..'
}
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path

function Test-JavaSourceGroup {
    param(
        [string]$RelativePath,
        [string]$Pattern
    )

    $absolute = Join-Path $RepositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $absolute)) {
        return $false
    }
    $sources = if (Test-Path -LiteralPath $absolute -PathType Leaf) {
        @(Get-Item -LiteralPath $absolute)
    } else {
        @(Get-ChildItem -LiteralPath $absolute -Recurse -File -Filter '*.java')
    }
    foreach ($source in $sources) {
        if ((Get-Content -LiteralPath $source.FullName -Raw -Encoding utf8) -match $Pattern) {
            return $true
        }
    }
    return $false
}

function Test-SourceContainsAll {
    param(
        [string]$RelativePath,
        [string[]]$Patterns
    )

    $absolute = Join-Path $RepositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) {
        return $false
    }
    $text = Get-Content -LiteralPath $absolute -Raw -Encoding utf8
    foreach ($pattern in $Patterns) {
        if ($text -notmatch $pattern) {
            return $false
        }
    }
    return $true
}

$checks = [ordered]@{
    M02_CONVERSATION_MODEL = Test-JavaSourceGroup `
        -RelativePath 'ai-service\src\main\java\com\javaee\aiservice\conversation\model' `
        -Pattern '(?m)\b(?:record|class)\s+Conversation(?:Message)?\b'
    M02_CONVERSATION_STORE = Test-JavaSourceGroup `
        -RelativePath 'ai-service\src\main\java\com\javaee\aiservice\conversation\store' `
        -Pattern '(?m)\bclass\s+JdbcConversationStore\b'
    M03_RUNTIME_STATE = Test-JavaSourceGroup `
        -RelativePath 'ai-service\src\main\java\com\javaee\aiservice\conversation\runtime' `
        -Pattern '(?m)\bclass\s+RuntimeStateService\b'
    M04_MEMORY_CONTEXT = Test-JavaSourceGroup `
        -RelativePath 'ai-service\src\main\java\com\javaee\aiservice\memory\context' `
        -Pattern '(?m)\bclass\s+MemoryContextBuilder\b'
    M05_STRUCTURED_SUMMARY = Test-JavaSourceGroup `
        -RelativePath 'ai-service\src\main\java\com\javaee\aiservice\memory\summary' `
        -Pattern '(?m)\bclass\s+ConversationSummaryService\b'
    M06_STRUCTURED_CONVERSATION_API = Test-JavaSourceGroup `
        -RelativePath 'ai-service\src\main\java\com\javaee\aiservice\conversation\api\ConversationController.java' `
        -Pattern '(?s)@RequestMapping\s*\(\s*["'']/?api/ai/conversations["'']\s*\)'
    M06A_FRONTEND_CONVERSATION_API = Test-SourceContainsAll `
        -RelativePath 'web\src\api\conversation.js' `
        -Patterns @('/ai/conversations', 'listMessages', 'deleteConversation')
    M06A_FRONTEND_SESSION_STATE = Test-SourceContainsAll `
        -RelativePath 'web\src\composables\useConversationSession.js' `
        -Patterns @('useConversationSession', 'conversationId', 'clientRequestId')
    M06C_GLOBAL_ASSISTANT_UI = Test-SourceContainsAll `
        -RelativePath 'web\src\views\components\GlobalAiAssistant.vue' `
        -Patterns @(
            'useConversationWorkspace', 'activeConversations', 'loadOlderMessages',
            'createConversation', 'archiveConversation', 'deleteConversation', 'clientRequestId')
    M06C_EDITOR_UI = Test-SourceContainsAll `
        -RelativePath 'web\src\views\editor\EditorView.vue' `
        -Patterns @(
            'useConversationWorkspace', 'activeConversations', 'loadOlderMessages',
            'createConversation', 'archiveConversation', 'deleteConversation', 'clientRequestId')
    M07_INTEGRATED_GATE = Test-JavaSourceGroup `
        -RelativePath 'ai-service\src\test\java\com\javaee\aiservice\memory\acceptance\MemoryV1IntegratedGateTest.java' `
        -Pattern '(?m)\bclass\s+MemoryV1IntegratedGateTest\b'
}

$missing = @($checks.GetEnumerator() | Where-Object { -not $_.Value } | ForEach-Object { $_.Key })
$result = [ordered]@{
    ready = ($missing.Count -eq 0)
    required = @($checks.Keys)
    missing = $missing
}

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $resolvedOutput
    [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resolvedOutput -Encoding utf8
}

if ($missing.Count -gt 0) {
    Write-Host "[PENDING_INTEGRATION] Missing required Memory V1 evidence: $($missing -join ', ')"
    exit 2
}

Write-Host '[PASS] Memory V1 M02-M06 source groups and the M07 integrated gate are present.'
exit 0
