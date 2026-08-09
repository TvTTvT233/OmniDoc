package com.javaee.aiservice.rag.vector.contract;

/**
 * Internal cursor request used to export the local baseline during migration.
 */
public record VectorExportRequest(String cursor, int batchSize, EmbeddingSpec assumedEmbedding) {

    public VectorExportRequest {
        cursor = cursor == null ? "" : cursor;
        if (batchSize <= 0 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        if (assumedEmbedding == null) {
            throw new IllegalArgumentException("assumedEmbedding must not be null");
        }
    }
}
