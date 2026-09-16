Set-StrictMode -Version Latest

function Get-MemoryM03SourceBundle {
    param([string]$RepositoryRoot)

    $runtimeRoot = Join-Path $RepositoryRoot 'ai-service\src\main\java\com\javaee\aiservice\conversation\runtime'
    $conversationRoot = Join-Path $RepositoryRoot 'ai-service\src\main\java\com\javaee\aiservice\conversation'
    $agentPath = Join-Path $RepositoryRoot 'ai-service\src\main\java\com\javaee\aiservice\agent\execution\AgentExecutionService.java'

    $runtimeSources = @()
    if (Test-Path -LiteralPath $runtimeRoot -PathType Container) {
        $runtimeSources = @(Get-ChildItem -LiteralPath $runtimeRoot -Recurse -File -Filter '*.java')
    }

    $conversationSources = @()
    if (Test-Path -LiteralPath $conversationRoot -PathType Container) {
        $conversationSources = @(Get-ChildItem -LiteralPath $conversationRoot -Recurse -File -Filter '*.java' |
            Where-Object { -not $_.FullName.StartsWith($runtimeRoot, [System.StringComparison]::OrdinalIgnoreCase) })
    }

    $agentSources = @()
    if (Test-Path -LiteralPath $agentPath -PathType Leaf) {
        $agentSources = @(Get-Item -LiteralPath $agentPath)
    }

    return [pscustomobject]@{
        Runtime = (($runtimeSources | ForEach-Object {
            Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8
        }) -join "`n")
        Conversation = (($conversationSources | ForEach-Object {
            Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8
        }) -join "`n")
        Agent = (($agentSources | ForEach-Object {
            Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8
        }) -join "`n")
    }
}

function Get-MemoryJavaMethodBodies {
    param(
        [string]$SourceText,
        [string]$MethodName
    )

    if ([string]::IsNullOrWhiteSpace($SourceText)) {
        return @()
    }

    $escapedName = [regex]::Escape($MethodName)
    $signature = "(?m)\b(?:public|protected|private)\s+(?:static\s+)?[\w<>, ?\[\].]+\s+$escapedName\s*\("
    $bodies = [System.Collections.Generic.List[string]]::new()
    foreach ($match in [regex]::Matches($SourceText, $signature)) {
        $openingBrace = $SourceText.IndexOf('{', $match.Index + $match.Length)
        if ($openingBrace -lt 0) {
            continue
        }
        $depth = 0
        for ($index = $openingBrace; $index -lt $SourceText.Length; $index++) {
            if ($SourceText[$index] -eq '{') {
                $depth++
            } elseif ($SourceText[$index] -eq '}') {
                $depth--
                if ($depth -eq 0) {
                    $bodies.Add($SourceText.Substring($match.Index, $index - $match.Index + 1))
                    break
                }
            }
        }
    }
    return $bodies.ToArray()
}

function Test-MemoryOwnerBeforeAction {
    param(
        [string]$MethodBody,
        [string]$ReadPattern,
        [string]$ActionPattern
    )

    if ([string]::IsNullOrWhiteSpace($MethodBody)) {
        return $false
    }
    $read = [regex]::Match($MethodBody, $ReadPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $read.Success) {
        return $false
    }

    $ownerPattern = '(?:assert|require|verify|validate|check)[A-Za-z0-9_]*(?:Owner|Ownership|Access)[A-Za-z0-9_]*\s*\('
    $owner = [regex]::Matches(
        $MethodBody,
        $ownerPattern,
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    ) | Where-Object { $_.Index -gt $read.Index } | Select-Object -First 1
    $action = [regex]::Matches(
        $MethodBody,
        $ActionPattern,
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    ) | Where-Object { $_.Index -gt $read.Index } | Select-Object -First 1

    return $null -ne $owner -and $null -ne $action -and $owner.Index -lt $action.Index
}

function Test-MemoryM03ProductionReadiness {
    param(
        [string]$RuntimeText,
        [string]$ConversationText,
        [string]$AgentText
    )

    $required = @(
        'M03_RUNTIME_STATE_MODEL',
        'M03_RUNTIME_STATE_SERVICE',
        'M03_RUNTIME_STATE_REDIS',
        'M03_RUNTIME_STATE_WHITELIST',
        'M03_RUNTIME_STATE_BOUNDED_TTL',
        'M03_RUNTIME_CLEAR_CONVERSATION',
        'M03_CONVERSATION_DELETE_RUNTIME_CASCADE',
        'M03_TASK_SNAPSHOT_DELETE_CASCADE',
        'M03_APPROVAL_REFERENCE_DELETE_CASCADE',
        'M03_CONTINUATION_OWNER_BEFORE_STATE_USE',
        'M03_APPROVAL_CONFIRM_OWNER_BEFORE_MUTATION',
        'M03_APPROVAL_CANCEL_OWNER_BEFORE_MUTATION'
    )
    $missing = [System.Collections.Generic.List[string]]::new()

    if ($RuntimeText -notmatch '(?m)\b(?:record|class)\s+RuntimeState(?:Snapshot|Update|Scope)?\b') {
        $missing.Add('M03_RUNTIME_STATE_MODEL')
    }
    if ($RuntimeText -notmatch '(?m)\b(?:class|interface)\s+\w*RuntimeState\w*Service\b') {
        $missing.Add('M03_RUNTIME_STATE_SERVICE')
    }
    if ($RuntimeText -notmatch '(?m)\bRedisTemplate\s*<') {
        $missing.Add('M03_RUNTIME_STATE_REDIS')
    }
    if ($RuntimeText -notmatch '(?i)RuntimeStateField|allowedFields|supportedFields|fieldWhitelist|EnumSet\s*<') {
        $missing.Add('M03_RUNTIME_STATE_WHITELIST')
    }
    if ($RuntimeText -notmatch '(?i)Duration|expiresAt|\bttl\b' -or
            $RuntimeText -notmatch '(?i)expire|expiration|ofMinutes|ofHours') {
        $missing.Add('M03_RUNTIME_STATE_BOUNDED_TTL')
    }
    if ($RuntimeText -notmatch '(?i)\bclearConversation\s*\(') {
        $missing.Add('M03_RUNTIME_CLEAR_CONVERSATION')
    }

    $cascadeFound = $false
    foreach ($methodName in @('delete', 'deleteConversationForUser', 'deleteConversation', 'permanentlyDelete')) {
        foreach ($body in @(Get-MemoryJavaMethodBodies -SourceText $ConversationText -MethodName $methodName)) {
            if ($body -match '(?i)\bclearConversation\s*\(') {
                $cascadeFound = $true
                break
            }
        }
        if ($cascadeFound) { break }
    }
    if (-not $cascadeFound) {
        $missing.Add('M03_CONVERSATION_DELETE_RUNTIME_CASCADE')
    }
    if ($RuntimeText -notmatch '(?is)taskRegistry\s*\.\s*findByConversationId\w*\s*\(.*taskRegistry\s*\.\s*delete\w*\s*\(') {
        $missing.Add('M03_TASK_SNAPSHOT_DELETE_CASCADE')
    }
    if ($RuntimeText -notmatch '(?is)(?:approvalService|agentApprovalService)\s*\.\s*cancel\w*\s*\(') {
        $missing.Add('M03_APPROVAL_REFERENCE_DELETE_CASCADE')
    }

    $executeBody = @(Get-MemoryJavaMethodBodies -SourceText $AgentText -MethodName 'execute') |
        Select-Object -First 1
    $continuationReady = Test-MemoryOwnerBeforeAction `
        -MethodBody $executeBody `
        -ReadPattern 'taskRegistry\s*\.\s*(?:get|require|getOwned)\s*\(\s*continueTraceId' `
        -ActionPattern 'runtimeStateService\s*\.\s*(?:readRequired|consume\w*|update\w*|clear\w*)\s*\(|taskRegistry\s*\.\s*(?:save|cancel|delete)\s*\(|agentApprovalService\s*\.\s*(?:consume|cancel|confirm)\w*\s*\(|(?:existingSnapshot|snapshot)\s*\.\s*put\s*\('
    if (-not $continuationReady) {
        $missing.Add('M03_CONTINUATION_OWNER_BEFORE_STATE_USE')
    }

    $confirmBody = @(Get-MemoryJavaMethodBodies -SourceText $AgentText -MethodName 'confirmApproval') |
        Select-Object -First 1
    $confirmReady = Test-MemoryOwnerBeforeAction `
        -MethodBody $confirmBody `
        -ReadPattern 'taskRegistry\s*\.\s*(?:findByApprovalToken|getApproval)\s*\(' `
        -ActionPattern 'agentApprovalService\s*\.\s*(?:consume|cancel|confirm)\w*\s*\(|taskRegistry\s*\.\s*(?:save|cancel|delete)\s*\(|(?:snapshot|pending)\s*\.\s*put\s*\(|\bexecute\s*\('
    if (-not $confirmReady) {
        $missing.Add('M03_APPROVAL_CONFIRM_OWNER_BEFORE_MUTATION')
    }

    $cancelBody = @(Get-MemoryJavaMethodBodies -SourceText $AgentText -MethodName 'cancelApproval') |
        Select-Object -First 1
    $cancelReady = Test-MemoryOwnerBeforeAction `
        -MethodBody $cancelBody `
        -ReadPattern 'taskRegistry\s*\.\s*(?:findByApprovalToken|getApproval)\s*\(' `
        -ActionPattern 'agentApprovalService\s*\.\s*cancel\w*\s*\(|taskRegistry\s*\.\s*(?:save|cancel|delete)\s*\(|snapshot\s*\.\s*put\s*\('
    if (-not $cancelReady) {
        $missing.Add('M03_APPROVAL_CANCEL_OWNER_BEFORE_MUTATION')
    }

    return [pscustomobject]@{
        Ready = $missing.Count -eq 0
        Required = $required
        Missing = $missing.ToArray()
    }
}
