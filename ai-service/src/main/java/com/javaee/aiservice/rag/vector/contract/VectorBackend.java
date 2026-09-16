package com.javaee.aiservice.rag.vector.contract;

import java.util.List;

/**
 * Boundary between OmniDoc RAG orchestration and a concrete vector index implementation.
 */
public interface VectorBackend {

    VectorBackendType type();

    void upsert(VectorRecord record);

    default void upsertBatch(List<VectorRecord> records) {
        if (records == null) {
            throw new IllegalArgumentException("records must not be null");
        }
        records.forEach(this::upsert);
    }

    List<VectorMatch> search(VectorQuery query);

    void deleteById(String id, VectorScope scope);

    long deleteByDocument(String documentId, VectorScope scope);

    VectorBackendHealth health();
}
