package com.javaee.aiservice.memory.context;

import java.util.Optional;

public interface ConversationSummaryProvider {

    Optional<MemorySummaryView> findOwned(String conversationId, String userId);
}
