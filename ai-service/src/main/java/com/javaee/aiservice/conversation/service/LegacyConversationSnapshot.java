package com.javaee.aiservice.conversation.service;

import java.util.List;

public record LegacyConversationSnapshot(
        String conversationId,
        String userId,
        List<String> messages
) {
    public LegacyConversationSnapshot {
        messages = List.copyOf(messages);
    }
}
