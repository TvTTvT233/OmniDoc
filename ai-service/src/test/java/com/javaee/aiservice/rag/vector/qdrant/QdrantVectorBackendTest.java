package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import io.grpc.Status;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantVectorBackendTest {

    private static final EmbeddingSpec EMBEDDING = new EmbeddingSpec("fixture-embedding", 3, 1);
    private FakeQdrantOperations operations;
    private QdrantVectorBackend backend;

    @BeforeEach
    void setUp() {
        operations = new FakeQdrantOperations();
        QdrantProperties properties = new QdrantProperties();
        properties.setCollectionAlias("test_qv02_active");
        properties.setBatchSize(2);
        QdrantCollectionManager manager = new QdrantCollectionManager(
                operations,
                properties,
                EMBEDDING,
                new QdrantCollectionNameFactory());
        backend = new QdrantVectorBackend(
                operations,
                properties,
                manager,
                new UuidV5VectorPointIdFactory());
    }

    @Test
    void repeatedUpsertUsesTheSamePointRatherThanCreatingVersions() {
        VectorRecord record = record("segment-1", "document-1", "user-a", "kb-a");

        backend.upsert(record);
        String firstPointId = operations.storedPoints.keySet().iterator().next();
        backend.upsert(record);

        assertThat(operations.storedPoints).hasSize(1);
        assertThat(operations.storedPoints).containsKey(firstPointId);
        assertThat(operations.upsertBatchSizes).containsExactly(1, 1);
    }

    @Test
    void sameLogicalIdInDifferentScopesProducesDifferentPoints() {
        backend.upsertBatch(List.of(
                record("segment-1", "document-1", "user-a", "kb-a"),
                record("segment-1", "document-1", "user-b", "kb-a"),
                record("segment-1", "document-1", "user-a", "kb-b")));

        assertThat(operations.storedPoints).hasSize(3);
        assertThat(operations.upsertBatchSizes).containsExactly(2, 1);
    }

    @Test
    void queryCarriesMandatoryTenantAndDocumentFiltersToQdrant() {
        VectorFilter filter = new VectorFilter(
                new VectorScope("user-a", "kb-a"),
                "document-1",
                Map.of("category", "award"));

        backend.search(new VectorQuery(new float[]{1, 0, 0}, 5, filter, EMBEDDING));

        assertThat(operations.lastQuery.getCollectionName()).isEqualTo("test_qv02_active");
        assertThat(operations.lastQuery.hasFilter()).isTrue();
        Set<String> fields = operations.lastQuery.getFilter().getMustList().stream()
                .filter(Condition::hasField)
                .map(condition -> condition.getField().getKey())
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder(
                "userId", "knowledgeBaseId", "documentId", "category");
        assertThat(operations.lastQuery.getLimit()).isEqualTo(5);
    }

    @Test
    void deleteByIdCombinesScopedPointIdAndTenantFilterOnTheServer() {
        backend.deleteById("segment-1", new VectorScope("user-a", "kb-a"));

        assertThat(operations.lastDeleteFilter.getMustList())
                .filteredOn(Condition::hasField)
                .extracting(condition -> condition.getField().getKey())
                .containsExactlyInAnyOrder("userId", "knowledgeBaseId");
        assertThat(operations.lastDeleteFilter.getMustList())
                .anyMatch(Condition::hasHasId);
    }

    @Test
    void deleteByDocumentCountsThenDeletesWithTheSameTenantFilter() {
        operations.countResult = 2;

        long deleted = backend.deleteByDocument(
                "document-1",
                new VectorScope("user-a", "kb-a"));

        assertThat(deleted).isEqualTo(2);
        assertThat(operations.lastDeleteFilter.getMustList())
                .extracting(condition -> condition.getField().getKey())
                .containsExactlyInAnyOrder("userId", "knowledgeBaseId", "documentId");
    }

    @Test
    void migrationCountValidatesEmbeddingAndCountsTheActiveCollection() {
        operations.countResult = 17;

        assertThat(backend.count(EMBEDDING)).isEqualTo(17);
        assertThatThrownBy(() -> backend.count(new EmbeddingSpec("other", 3, 1)))
                .isInstanceOfSatisfying(VectorBackendException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(VectorErrorCode.VECTOR_CONFIGURATION_ERROR));
    }

    @Test
    void mapsPayloadBackToLogicalMatchWithoutReservedMetadata() {
        Map<String, Value> payload = Map.of(
                "id", Value.newBuilder().setStringValue("segment-1").build(),
                "documentId", Value.newBuilder().setStringValue("document-1").build(),
                "userId", Value.newBuilder().setStringValue("user-a").build(),
                "knowledgeBaseId", Value.newBuilder().setStringValue("kb-a").build(),
                "embeddingModel", Value.newBuilder().setStringValue("fixture-embedding").build(),
                "schemaVersion", Value.newBuilder().setIntegerValue(1).build(),
                "title", Value.newBuilder().setStringValue("fixture title").build());
        operations.queryResults = List.of(ScoredPoint.newBuilder()
                .setScore(0.75f)
                .putAllPayload(payload)
                .build());

        List<VectorMatch> matches = backend.search(query());

        assertThat(matches).containsExactly(new VectorMatch(
                "segment-1",
                "document-1",
                0.75f,
                Map.of("title", "fixture title")));
    }

    @Test
    void rejectsDimensionMismatchBeforeWriting() {
        VectorRecord wrongSpace = new VectorRecord(
                "segment-1",
                "document-1",
                new float[]{1, 0, 0, 0},
                new VectorScope("user-a", "kb-a"),
                new EmbeddingSpec("fixture-embedding", 4, 1),
                Map.of());

        assertThatThrownBy(() -> backend.upsert(wrongSpace))
                .isInstanceOfSatisfying(VectorBackendException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(VectorErrorCode.VECTOR_DIMENSION_MISMATCH));
        assertThat(operations.upsertBatchSizes).isEmpty();
    }

    @Test
    void mapsAuthenticationTimeoutAndUnavailableFailuresToStableErrors() {
        operations.failNext("query", new QdrantClientCallException(
                Status.UNAUTHENTICATED.asRuntimeException()));
        assertThatThrownBy(() -> backend.search(query()))
                .isInstanceOfSatisfying(VectorBackendException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(VectorErrorCode.VECTOR_AUTH_FAILED);
                    assertThat(exception.isRetryable()).isFalse();
                });

        operations.failNext("upsert", new QdrantClientCallException(new TimeoutException()));
        assertThatThrownBy(() -> backend.upsert(
                record("segment-1", "document-1", "user-a", "kb-a")))
                .isInstanceOfSatisfying(VectorBackendException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(VectorErrorCode.VECTOR_TIMEOUT);
                    assertThat(exception.isRetryable()).isTrue();
                });

        operations.failNext("health", new QdrantClientCallException(
                Status.UNAVAILABLE.asRuntimeException()));
        VectorBackendHealth health = backend.health();
        assertThat(health.available()).isFalse();
        assertThat(health.message()).isEqualTo("VECTOR_BACKEND_UNAVAILABLE");
        assertThat(health.details().toString()).doesNotContain("api", "secret", "key");
    }

    private static VectorQuery query() {
        return new VectorQuery(
                new float[]{1, 0, 0},
                3,
                VectorFilter.forKnowledgeBase("user-a", "kb-a"),
                EMBEDDING);
    }

    private static VectorRecord record(
            String id,
            String documentId,
            String userId,
            String knowledgeBaseId
    ) {
        return new VectorRecord(
                id,
                documentId,
                new float[]{1, 0, 0},
                new VectorScope(userId, knowledgeBaseId),
                EMBEDDING,
                Map.of("source", "fixture"));
    }
}
