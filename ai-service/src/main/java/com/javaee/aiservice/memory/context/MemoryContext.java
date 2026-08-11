package com.javaee.aiservice.memory.context;

import com.javaee.aiservice.conversation.model.ConversationMessage;

import java.util.List;
import java.util.Map;

public record MemoryContext(
        String rendered,
        int estimatedTokens,
        int inputBudgetTokens,
        long summaryCoverageToSequence,
        List<ConversationMessage> recentMessages,
        List<MemoryToolExchange> currentTraceTools,
        List<String> trimmingReasons,
        Map<String, Integer> blockTokenCounts
) {
    public MemoryContext {
        recentMessages = List.copyOf(recentMessages);
        currentTraceTools = List.copyOf(currentTraceTools);
        trimmingReasons = List.copyOf(trimmingReasons);
        blockTokenCounts = Map.copyOf(blockTokenCounts);
    }
}
