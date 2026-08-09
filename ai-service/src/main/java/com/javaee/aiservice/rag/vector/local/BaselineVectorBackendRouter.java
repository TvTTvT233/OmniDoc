package com.javaee.aiservice.rag.vector.local;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendRouter;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Baseline router: all operations stay on the existing Redis + JVM HNSW backend. */
@Component
@ConditionalOnProperty(prefix = "ai.vector", name = "mode", havingValue = "baseline", matchIfMissing = true)
public final class BaselineVectorBackendRouter implements VectorBackendRouter, LegacyVectorOperations {

    private final LocalRedisHnswVectorBackend backend;

    public BaselineVectorBackendRouter(LocalRedisHnswVectorBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        this.backend = backend;
    }

    @Override
    public VectorStoreMode mode() {
        return VectorStoreMode.BASELINE;
    }

    @Override
    public VectorBackendType type() {
        return backend.type();
    }

    @Override
    public void upsert(VectorRecord record) {
        backend.upsert(record);
    }

    @Override
    public void upsertBatch(List<VectorRecord> records) {
        backend.upsertBatch(records);
    }

    @Override
    public List<VectorMatch> search(VectorQuery query) {
        return backend.search(query);
    }

    @Override
    public void deleteById(String id, VectorScope scope) {
        backend.deleteById(id, scope);
    }

    @Override
    public long deleteByDocument(String documentId, VectorScope scope) {
        return backend.deleteByDocument(documentId, scope);
    }

    @Override
    public VectorBackendHealth health() {
        return backend.health();
    }

    @Override
    public List<VectorMatch> searchLegacy(float[] vector, int topK, Map<String, Object> filters,
                                          EmbeddingSpec embedding) {
        return backend.searchLegacy(vector, topK, filters, embedding);
    }

    @Override
    public void deleteLegacy(String id) {
        backend.deleteLegacy(id);
    }
}
