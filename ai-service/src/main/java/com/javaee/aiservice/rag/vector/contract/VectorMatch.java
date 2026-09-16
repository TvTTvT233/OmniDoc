package com.javaee.aiservice.rag.vector.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalized vector search result. Higher scores are more relevant.
 */
public record VectorMatch(String id, String documentId, float score, Map<String, Object> metadata) {

    public VectorMatch {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (!Float.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
