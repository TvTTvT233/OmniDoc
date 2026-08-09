package com.javaee.aiservice.rag.vector.routing;

import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sanitized shadow telemetry. It intentionally has no vector, metadata, document text, scope or credential fields.
 */
public record ShadowObservation(
        ShadowOperation operation,
        int requestedTopK,
        List<String> logicalIds,
        List<String> baselineIds,
        List<String> qdrantIds,
        long baselineDurationNanos,
        long qdrantDurationNanos,
        int overlapCount,
        double overlapAtK,
        VectorErrorCode errorCode
) {

    public ShadowObservation {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        if (requestedTopK < 0 || requestedTopK > 100) {
            throw new IllegalArgumentException("requestedTopK must be between 0 and 100");
        }
        logicalIds = sanitizedIds(logicalIds);
        baselineIds = sanitizedIds(baselineIds);
        qdrantIds = sanitizedIds(qdrantIds);
        if (baselineDurationNanos < 0L || qdrantDurationNanos < 0L) {
            throw new IllegalArgumentException("durations must not be negative");
        }
        if (overlapCount < 0 || overlapCount > requestedTopK) {
            throw new IllegalArgumentException("overlapCount must be between 0 and requestedTopK");
        }
        if (!Double.isFinite(overlapAtK) || overlapAtK < 0.0d || overlapAtK > 1.0d) {
            throw new IllegalArgumentException("overlapAtK must be between 0 and 1");
        }
    }

    public boolean successfulComparison() {
        return operation == ShadowOperation.SEARCH && errorCode == null;
    }

    private static List<String> sanitizedIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(ShadowObservation::sanitizeIdForTelemetry)
                .distinct()
                .limit(100)
                .toList();
    }

    private static String sanitizeIdForTelemetry(String id) {
        StringBuilder sanitized = new StringBuilder(Math.min(id.length(), 128));
        for (int index = 0; index < id.length() && sanitized.length() < 128; index++) {
            char character = id.charAt(index);
            sanitized.append(Character.isISOControl(character) ? '?' : character);
        }
        return sanitized.toString();
    }

    static ShadowObservation search(
            int topK,
            List<String> baselineIds,
            List<String> qdrantIds,
            long baselineDurationNanos,
            long qdrantDurationNanos,
            VectorErrorCode errorCode
    ) {
        Set<String> qdrant = new LinkedHashSet<>(qdrantIds);
        int overlap = (int) baselineIds.stream().limit(topK).filter(qdrant::contains).count();
        double ratio = topK == 0 ? 0.0d : (double) overlap / topK;
        return new ShadowObservation(
                ShadowOperation.SEARCH,
                topK,
                List.of(),
                baselineIds,
                qdrantIds,
                baselineDurationNanos,
                qdrantDurationNanos,
                overlap,
                ratio,
                errorCode
        );
    }

    static ShadowObservation operation(
            ShadowOperation operation,
            List<String> logicalIds,
            long baselineDurationNanos,
            long qdrantDurationNanos,
            VectorErrorCode errorCode
    ) {
        return new ShadowObservation(
                operation,
                0,
                logicalIds,
                List.of(),
                List.of(),
                baselineDurationNanos,
                qdrantDurationNanos,
                0,
                0.0d,
                errorCode
        );
    }
}
