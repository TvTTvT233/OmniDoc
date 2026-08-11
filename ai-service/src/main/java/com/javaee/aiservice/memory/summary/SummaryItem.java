package com.javaee.aiservice.memory.summary;

import java.util.List;

public record SummaryItem(
        String id,
        String content,
        String status,
        List<SummarySourceRef> sourceRefs,
        long lastUpdatedSeq
) {
    public SummaryItem {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
