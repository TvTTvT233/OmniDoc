package com.javaee.aiservice.conversation.runtime;

/**
 * Typed, trace-scoped Runtime State view.
 */
public record RuntimeStateSnapshot(
        RuntimeStateScope scope,
        String currentDocumentId,
        String selectedText,
        String pageContext,
        RuntimeStateUpdate.PendingApprovalReference pendingApproval,
        RuntimeStateUpdate.CurrentToolState currentToolState
) {

    public static RuntimeStateSnapshot empty(RuntimeStateScope scope) {
        return new RuntimeStateSnapshot(scope, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return currentDocumentId == null
                && selectedText == null
                && pageContext == null
                && pendingApproval == null
                && currentToolState == null;
    }
}
