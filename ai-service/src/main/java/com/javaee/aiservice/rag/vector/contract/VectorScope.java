package com.javaee.aiservice.rag.vector.contract;

/**
 * Mandatory tenant boundary for every vector write, query and delete operation.
 */
public record VectorScope(String userId, String knowledgeBaseId) {

    public VectorScope {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new IllegalArgumentException("knowledgeBaseId must not be blank");
        }
    }
}
