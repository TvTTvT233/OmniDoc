package com.javaee.aiservice.rag.vector.routing;

import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;

import java.util.Map;

public record ShadowMetricsSnapshot(
        long sampleCount,
        long successfulComparisonCount,
        long operationCount,
        double averageOverlapAtK,
        double averageBaselineDurationMillis,
        double averageQdrantDurationMillis,
        Map<VectorErrorCode, Long> errors
) {

    public ShadowMetricsSnapshot {
        errors = errors == null ? Map.of() : Map.copyOf(errors);
    }
}
