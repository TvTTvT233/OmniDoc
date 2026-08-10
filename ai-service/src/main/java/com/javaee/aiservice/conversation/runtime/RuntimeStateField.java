package com.javaee.aiservice.conversation.runtime;

import java.time.Duration;

/**
 * Frozen Runtime State whitelist. Runtime State is short-lived execution data, not Memory.
 */
public enum RuntimeStateField {
    CURRENT_DOCUMENT_ID("currentDocumentId", Lifecycle.SLIDING, Duration.ofMinutes(30), 512),
    SELECTED_TEXT("selectedText", Lifecycle.TURN_INPUT, Duration.ofMinutes(10), 16 * 1024),
    PAGE_CONTEXT("pageContext", Lifecycle.TURN_INPUT, Duration.ofMinutes(10), 4 * 1024),
    PENDING_APPROVAL("pendingApproval", Lifecycle.APPROVAL, Duration.ofMinutes(5), 1024),
    CURRENT_TOOL_STATE("currentToolState", Lifecycle.TRACE, Duration.ofHours(1), 2 * 1024);

    private final String wireName;
    private final Lifecycle lifecycle;
    private final Duration hardMaximumTtl;
    private final int hardMaximumBytes;

    RuntimeStateField(String wireName, Lifecycle lifecycle, Duration hardMaximumTtl, int hardMaximumBytes) {
        this.wireName = wireName;
        this.lifecycle = lifecycle;
        this.hardMaximumTtl = hardMaximumTtl;
        this.hardMaximumBytes = hardMaximumBytes;
    }

    public String wireName() {
        return wireName;
    }

    public Lifecycle lifecycle() {
        return lifecycle;
    }

    public Duration hardMaximumTtl() {
        return hardMaximumTtl;
    }

    public int hardMaximumBytes() {
        return hardMaximumBytes;
    }

    public enum Lifecycle {
        TURN_INPUT,
        SLIDING,
        APPROVAL,
        TRACE
    }
}
