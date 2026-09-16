package com.javaee.aiservice.memory.summary;

import java.util.Map;

public record SummarySourceRef(
        SummarySourceType type,
        String id,
        String versionId,
        Map<String, Object> locator,
        String contentHash
) {
    public SummarySourceRef {
        locator = locator == null ? Map.of() : Map.copyOf(locator);
    }
}
