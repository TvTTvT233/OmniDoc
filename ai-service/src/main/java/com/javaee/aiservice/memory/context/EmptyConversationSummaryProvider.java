package com.javaee.aiservice.memory.context;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnMissingBean(ConversationSummaryProvider.class)
public final class EmptyConversationSummaryProvider implements ConversationSummaryProvider {

    @Override
    public Optional<MemorySummaryView> findOwned(String conversationId, String userId) {
        return Optional.empty();
    }
}
