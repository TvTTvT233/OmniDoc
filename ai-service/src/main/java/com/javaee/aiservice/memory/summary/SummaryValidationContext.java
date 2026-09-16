package com.javaee.aiservice.memory.summary;

import com.javaee.aiservice.conversation.model.ConversationMessage;

import java.util.List;
import java.time.Instant;

public record SummaryValidationContext(
        String conversationId,
        long expectedSummaryVersion,
        long expectedPreviousSummaryVersion,
        long expectedFromSequence,
        long expectedToSequence,
        String expectedSourceDigest,
        Instant expectedGeneratedAt,
        List<ConversationMessage> coveredMessages,
        StructuredConversationSummary previousSummary
) {
    public SummaryValidationContext {
        coveredMessages = List.copyOf(coveredMessages);
    }
}
