package com.javaee.aiservice.memory.summary;

import java.time.Instant;

public record StoredConversationSummary(
        String conversationId,
        String schemaVersion,
        long summaryVersion,
        long fromSequence,
        long toSequence,
        String sourceDigest,
        String summaryJson,
        Instant generatedAt,
        Instant updatedAt
) {
}
