package com.javaee.aiservice.conversation.model;

import java.util.List;

public record ConversationMessagePage(
        List<ConversationMessage> items,
        Long nextBeforeSequence
) {
    public ConversationMessagePage {
        items = List.copyOf(items);
    }
}
