package com.javaee.aiservice.conversation.api;

public record ConversationResponse(
        String conversationId,
        String title,
        String status,
        String createdAt,
        String updatedAt
) {
}
