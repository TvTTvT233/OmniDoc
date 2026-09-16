package com.javaee.aiservice.memory.context;

public record MemoryTurnContext(
        String conversationId,
        String userId,
        String traceId,
        String turnId,
        String clientRequestId,
        long currentUserSequence
) {
}
