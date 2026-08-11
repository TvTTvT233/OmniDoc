package com.javaee.aiservice.memory.context;

public record MemoryToolExchange(
        String toolCallId,
        String callText,
        String resultText,
        boolean required
) {
}
