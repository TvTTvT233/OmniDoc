package com.javaee.aiservice.conversation.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ConversationMessage(
        String messageId,
        String conversationId,
        long sequence,
        String turnId,
        String clientRequestId,
        int turnOrdinal,
        ConversationRole role,
        ConversationMessageType messageType,
        String content,
        String traceId,
        String toolCallId,
        Map<String, Object> metadata,
        String contentHash,
        Instant createdAt
) {
    public ConversationMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(content, "content");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
