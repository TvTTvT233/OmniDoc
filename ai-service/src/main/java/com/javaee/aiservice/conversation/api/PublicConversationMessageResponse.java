package com.javaee.aiservice.conversation.api;

import java.util.Map;

public record PublicConversationMessageResponse(
        String messageId,
        String conversationId,
        long sequence,
        String role,
        String messageType,
        String content,
        String traceId,
        String toolCallId,
        Map<String, Object> metadata,
        String createdAt,
        java.util.List<com.javaee.aiservice.consistency.IndexContracts.Reference> references
) {
    public PublicConversationMessageResponse {
        metadata = Map.copyOf(metadata);
        references = references == null ? java.util.List.of() : java.util.List.copyOf(references);
    }
    public PublicConversationMessageResponse(String messageId, String conversationId, long sequence, String role,
            String messageType, String content, String traceId, String toolCallId, Map<String,Object> metadata, String createdAt) {
        this(messageId, conversationId, sequence, role, messageType, content, traceId, toolCallId, metadata, createdAt, java.util.List.of());
    }
}
