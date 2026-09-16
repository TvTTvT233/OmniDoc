package com.javaee.aiservice.conversation.api;

import java.util.List;

public record ConversationPageResponse(
        List<ConversationResponse> items,
        String nextCursor,
        boolean hasMore
) {
    public ConversationPageResponse {
        items = List.copyOf(items);
    }
}
