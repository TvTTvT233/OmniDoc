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
        String createdAt
) {
    public PublicConversationMessageResponse {
        metadata = Map.copyOf(metadata);
    }
}
