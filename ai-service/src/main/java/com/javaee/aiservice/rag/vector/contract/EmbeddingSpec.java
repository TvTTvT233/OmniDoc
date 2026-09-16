package com.javaee.aiservice.rag.vector.contract;

/**
 * Identifies one compatible vector space. Vectors from different specifications must not share an index.
 */
public record EmbeddingSpec(String model, int dimension, int schemaVersion) {

    public EmbeddingSpec {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
    }
}
