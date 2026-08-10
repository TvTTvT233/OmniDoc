package com.javaee.aiservice.conversation.model;

import java.time.Instant;

public record ConversationCursor(Instant updatedAt, String conversationId) {
}
