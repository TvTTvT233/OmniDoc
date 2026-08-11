package com.javaee.aiservice.conversation.api;

import java.util.List;

public record ConversationMessagePageResponse(
        List<PublicConversationMessageResponse> items,
        Long nextBeforeSequence,
        boolean hasMore
) {
    public ConversationMessagePageResponse {
        items = List.copyOf(items);
    }
}
