package com.javaee.aiservice.rag.vector.contract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorContractTest {

    private static final EmbeddingSpec EMBEDDING = new EmbeddingSpec("fixture-embedding", 3, 1);
    private static final VectorScope SCOPE = new VectorScope("user-a", "kb-a");

    @Test
    void recordDefensivelyCopiesVectorAndMetadata() {
        float[] sourceVector = {1.0f, 0.0f, 0.0f};
        Map<String, Object> sourceMetadata = new java.util.LinkedHashMap<>();
        sourceMetadata.put("segmentIndex", 1);

        VectorRecord record = new VectorRecord(
                "segment-1", "document-1", sourceVector, SCOPE, EMBEDDING, sourceMetadata);

        sourceVector[0] = 0.0f;
        sourceMetadata.put("segmentIndex", 2);
        float[] returnedVector = record.vector();
        returnedVector[0] = 0.0f;

        assertThat(record.vector()).containsExactly(1.0f, 0.0f, 0.0f);
        assertThat(record.metadata()).containsEntry("segmentIndex", 1);
        assertThatThrownBy(() -> record.metadata().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void queryRequiresTenantFilterAndMatchingEmbeddingDimension() {
        assertThatThrownBy(() -> new VectorScope("", "kb-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> new VectorQuery(
                new float[]{1.0f, 0.0f}, 5,
                VectorFilter.forKnowledgeBase("user-a", "kb-a"), EMBEDDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void arbitraryFiltersCannotOverrideTenantBoundary() {
        assertThatThrownBy(() -> new VectorFilter(
                SCOPE, null, Map.of("userId", "user-b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved key");
        assertThatThrownBy(() -> new VectorRecord(
                "segment-1", "document-1", new float[]{1.0f, 0.0f, 0.0f},
                SCOPE, EMBEDDING, Map.of("knowledgeBaseId", "kb-b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved key");
    }

    @Test
    void defaultBatchUpsertPreservesOrder() {
        List<String> upserted = new ArrayList<>();
        VectorBackend backend = new StubBackend(upserted);

        backend.upsertBatch(List.of(record("segment-1"), record("segment-2")));

        assertThat(upserted).containsExactly("segment-1", "segment-2");
    }

    @Test
    void modesAndBackendsUseFrozenConfigurationNames() {
        assertThat(VectorStoreMode.fromConfig("shadow")).isEqualTo(VectorStoreMode.SHADOW);
        assertThat(VectorBackendType.fromConfig("qdrant")).isEqualTo(VectorBackendType.QDRANT);
        assertThat(VectorBackendType.LOCAL_REDIS_HNSW.configValue()).isEqualTo("local-redis-hnsw");
        assertThatThrownBy(() -> VectorStoreMode.fromConfig("fallback"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incompleteExportPageRequiresCursor() {
        assertThatThrownBy(() -> new VectorRecordPage(List.of(record("segment-1")), "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextCursor");
        assertThat(new VectorRecordPage(List.of(record("segment-1")), "", true).records())
                .hasSize(1);
    }

    private static VectorRecord record(String id) {
        return new VectorRecord(id, "document-1", new float[]{1.0f, 0.0f, 0.0f},
                SCOPE, EMBEDDING, Map.of());
    }

    private static final class StubBackend implements VectorBackend {
        private final List<String> upserted;

        private StubBackend(List<String> upserted) {
            this.upserted = upserted;
        }

        @Override
        public VectorBackendType type() {
            return VectorBackendType.LOCAL_REDIS_HNSW;
        }

        @Override
        public void upsert(VectorRecord record) {
            upserted.add(record.id());
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            return List.of();
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            return 0;
        }

        @Override
        public VectorBackendHealth health() {
            return new VectorBackendHealth(type(), true, "fixture", Map.of());
        }
    }
}
