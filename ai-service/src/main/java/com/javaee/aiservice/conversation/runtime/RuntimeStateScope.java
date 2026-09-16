package com.javaee.aiservice.conversation.runtime;

/**
 * Runtime State is isolated by both conversation and execution trace.
 */
public record RuntimeStateScope(String conversationId, String traceId) {

    private static final int MAX_CONVERSATION_ID_LENGTH = 64;
    private static final int MAX_TRACE_ID_LENGTH = 64;

    public RuntimeStateScope {
        conversationId = requireIdentifier(conversationId, "conversationId", MAX_CONVERSATION_ID_LENGTH);
        traceId = requireIdentifier(traceId, "traceId", MAX_TRACE_ID_LENGTH);
    }

    static String requireConversationId(String conversationId) {
        return requireIdentifier(conversationId, "conversationId", MAX_CONVERSATION_ID_LENGTH);
    }

    private static String requireIdentifier(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " contains a null character");
        }
        return normalized;
    }
}
