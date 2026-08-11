package com.javaee.aiservice.memory.context;

import java.util.List;
import java.util.Map;

public record MemoryContextRequest(
        String conversationId,
        String userId,
        String model,
        MemoryCallStage stage,
        String systemPolicy,
        String currentMessage,
        long currentMessageSequence,
        Map<String, Object> runtimeState,
        List<MemoryToolExchange> currentTraceTools,
        String callPayload
) {
    public MemoryContextRequest {
        runtimeState = runtimeState == null ? Map.of() : Map.copyOf(runtimeState);
        currentTraceTools = currentTraceTools == null ? List.of() : List.copyOf(currentTraceTools);
        callPayload = callPayload == null ? "" : callPayload;
    }
}
