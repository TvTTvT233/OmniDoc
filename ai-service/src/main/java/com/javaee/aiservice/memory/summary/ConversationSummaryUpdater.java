package com.javaee.aiservice.memory.summary;

public interface ConversationSummaryUpdater {

    SummaryUpdateResult maybeUpdate(String conversationId, String userId, String requestModel);
}
