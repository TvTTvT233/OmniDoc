package com.javaee.aiservice.rag.vector.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One provider-neutral vector point ready for idempotent upsert.
 */
public record VectorRecord(
        String id,
        String documentId,
        float[] vector,
        VectorScope scope,
        EmbeddingSpec embedding,
        Map<String, Object> metadata
) {

    private static final Set<String> RESERVED_METADATA_KEYS = Set.of(
            "id", "userId", "knowledgeBaseId", "documentId",
            "embeddingModel", "embeddingDimension", "schemaVersion");

    public VectorRecord {
        id = requireText(id, "id");
        documentId = requireText(documentId, "documentId");
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (embedding == null) {
            throw new IllegalArgumentException("embedding must not be null");
        }
        if (vector.length != embedding.dimension()) {
            throw new IllegalArgumentException("vector dimension does not match embedding specification");
        }
        vector = vector.clone();
        Map<String, Object> copy = metadata == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(metadata);
        for (String reservedKey : RESERVED_METADATA_KEYS) {
            if (copy.containsKey(reservedKey)) {
                throw new IllegalArgumentException("metadata must not override reserved key: " + reservedKey);
            }
        }
        metadata = Collections.unmodifiableMap(copy);
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
