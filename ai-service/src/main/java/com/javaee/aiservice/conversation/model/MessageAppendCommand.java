package com.javaee.aiservice.conversation.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MessageAppendCommand(
        String messageId,
        String turnId,
        String clientRequestId,
        int turnOrdinal,
        ConversationRole role,
        ConversationMessageType messageType,
        String content,
        String traceId,
        String toolCallId,
        Map<String, Object> metadata
) {
    public MessageAppendCommand {
        messageId = isBlank(messageId) ? UUID.randomUUID().toString() : messageId;
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(content, "content");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (turnOrdinal < 0) {
            throw new IllegalArgumentException("turnOrdinal must be non-negative");
        }
    }

    public static MessageAppendCommand text(
            String turnId,
            String clientRequestId,
            int turnOrdinal,
            ConversationRole role,
            String content
    ) {
        return new MessageAppendCommand(null, turnId, clientRequestId, turnOrdinal, role,
                ConversationMessageType.TEXT, content, null, null, Map.of());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
