package com.javaee.aiservice.memory.summary;

public record SummaryUpdateResult(
        SummaryUpdateStatus status,
        long previousVersion,
        long resultingVersion,
        long coverageToSequence,
        int sourceMessageCount,
        int estimatedSourceTokens,
        int attempts
) {
}
