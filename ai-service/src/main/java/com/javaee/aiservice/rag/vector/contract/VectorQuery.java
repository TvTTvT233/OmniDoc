package com.javaee.aiservice.rag.vector.contract;

/**
 * Provider-neutral nearest-neighbor query with a mandatory tenant filter.
 */
public record VectorQuery(float[] vector, int topK, VectorFilter filter, EmbeddingSpec embedding) {

    public VectorQuery {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        if (topK <= 0 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        if (filter == null) {
            throw new IllegalArgumentException("filter must not be null");
        }
        if (embedding == null) {
            throw new IllegalArgumentException("embedding must not be null");
        }
        if (vector.length != embedding.dimension()) {
            throw new IllegalArgumentException("query vector dimension does not match embedding specification");
        }
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }
}
