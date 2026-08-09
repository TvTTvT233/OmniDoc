package com.javaee.aiservice.rag.vector.contract;

import java.util.List;

/**
 * One idempotent migration page exported from the baseline backend.
 */
public record VectorRecordPage(List<VectorRecord> records, String nextCursor, boolean complete) {

    public VectorRecordPage {
        records = records == null ? List.of() : List.copyOf(records);
        nextCursor = nextCursor == null ? "" : nextCursor;
        if (!complete && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor must not be blank when export is incomplete");
        }
    }
}
