package com.javaee.aiservice.conversation.model;

import java.util.List;

public record ConversationPage(
        List<Conversation> items,
        ConversationCursor nextCursor
) {
    public ConversationPage {
        items = List.copyOf(items);
    }
}
