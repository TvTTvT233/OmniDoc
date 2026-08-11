package com.javaee.aiservice.memory.summary;

import java.util.Optional;

public interface ConversationSummaryStore {

    Optional<StoredConversationSummary> findOwned(String conversationId, String userId);

    SummaryWriteResult saveCas(StoredConversationSummary summary, long expectedPreviousVersion);
}
