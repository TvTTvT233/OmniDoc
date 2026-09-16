package com.javaee.aiservice.rag.vector.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Strongly typed search filter. Tenant keys cannot be overridden through arbitrary attributes.
 */
public record VectorFilter(VectorScope scope, String documentId, Map<String, Object> attributes) {

    private static final Set<String> RESERVED_KEYS = Set.of("userId", "knowledgeBaseId", "documentId");

    public VectorFilter {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        documentId = documentId == null || documentId.isBlank() ? null : documentId;
        Map<String, Object> copy = attributes == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(attributes);
        for (String reservedKey : RESERVED_KEYS) {
            if (copy.containsKey(reservedKey)) {
                throw new IllegalArgumentException("attributes must not override reserved key: " + reservedKey);
            }
        }
        attributes = Collections.unmodifiableMap(copy);
    }

    public static VectorFilter forKnowledgeBase(String userId, String knowledgeBaseId) {
        return new VectorFilter(new VectorScope(userId, knowledgeBaseId), null, Map.of());
    }

    public static VectorFilter forDocument(String userId, String knowledgeBaseId, String documentId) {
        return new VectorFilter(new VectorScope(userId, knowledgeBaseId), documentId, Map.of());
    }
}
