package com.javaee.aiservice.conversation.runtime;

/**
 * Typed Runtime State update. Deliberately does not expose an arbitrary Map extension point.
 * A null component means "leave unchanged"; use explicit clear operations to remove state.
 */
public record RuntimeStateUpdate(
        String currentDocumentId,
        String selectedText,
        String pageContext,
        PendingApprovalReference pendingApproval,
        CurrentToolState currentToolState
) {

    public boolean isEmpty() {
        return currentDocumentId == null
                && selectedText == null
                && pageContext == null
                && pendingApproval == null
                && currentToolState == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public record PendingApprovalReference(
            String referenceId,
            String toolName,
            long expiresAtEpochMilli
    ) {
    }

    public record CurrentToolState(
            String toolCallId,
            String toolName,
            ToolPhase phase,
            String statusSummary
    ) {
    }

    public enum ToolPhase {
        PLANNED,
        RUNNING,
        WAITING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public static final class Builder {
        private String currentDocumentId;
        private String selectedText;
        private String pageContext;
        private PendingApprovalReference pendingApproval;
        private CurrentToolState currentToolState;

        public Builder currentDocumentId(String value) {
            this.currentDocumentId = value;
            return this;
        }

        public Builder selectedText(String value) {
            this.selectedText = value;
            return this;
        }

        public Builder pageContext(String value) {
            this.pageContext = value;
            return this;
        }

        public Builder pendingApproval(PendingApprovalReference value) {
            this.pendingApproval = value;
            return this;
        }

        public Builder currentToolState(CurrentToolState value) {
            this.currentToolState = value;
            return this;
        }

        public RuntimeStateUpdate build() {
            return new RuntimeStateUpdate(
                    currentDocumentId,
                    selectedText,
                    pageContext,
                    pendingApproval,
                    currentToolState
            );
        }
    }
}
