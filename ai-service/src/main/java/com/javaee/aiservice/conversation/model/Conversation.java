package com.javaee.aiservice.conversation.model;

import java.time.Instant;
import java.util.Objects;

public record Conversation(
        String conversationId,
        String userId,
        String title,
        ConversationStatus status,
        long nextSequence,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        long version
) {
    public Conversation {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
