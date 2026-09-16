package com.javaee.aiservice.rag.vector.local;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorExportRequest;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorRecordPage;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalRedisHnswVectorBackendTest {

    private static final EmbeddingSpec EMBEDDING = new EmbeddingSpec("fixture-embedding", 3, 1);
    private static final VectorScope USER_A = new VectorScope("user-a", "kb-a");
    private static final VectorScope USER_B = new VectorScope("user-b", "kb-a");

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, Map<Object, Object>> metadata = new LinkedHashMap<>();
    private RedisTemplate<String, Object> redisTemplate;
    private LocalRedisHnswVectorBackend backend;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), org.mockito.ArgumentMatchers.any());
        when(valueOperations.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<Object, Object> copy = new LinkedHashMap<>();
            Map<?, ?> supplied = invocation.getArgument(1);
            supplied.forEach(copy::put);
            metadata.put(key, copy);
            return null;
        }).when(hashOperations).putAll(anyString(), anyMap());
        when(hashOperations.entries(anyString())).thenAnswer(invocation ->
                metadata.getOrDefault(invocation.getArgument(0), Map.of()));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return values.remove(key) != null | metadata.remove(key) != null;
        }).when(redisTemplate).delete(anyString());

        backend = new LocalRedisHnswVectorBackend(redisTemplate);
        ReflectionTestUtils.setField(backend, "defaultEmbeddingModel", EMBEDDING.model());
        ReflectionTestUtils.setField(backend, "defaultSchemaVersion", EMBEDDING.schemaVersion());
        ReflectionTestUtils.setField(backend, "hnswM", 8);
        ReflectionTestUtils.setField(backend, "hnswEfConstruction", 32);
        ReflectionTestUtils.setField(backend, "hnswEf", 16);
        ReflectionTestUtils.setField(backend, "hnswMaxItems", 64);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startupRebuildLoadsExistingRedisVectors() {
        values.put("vector:warm-segment", List.of(1.0f, 0.0f, 0.0f));
        metadata.put("metadata:warm-segment", new LinkedHashMap<>(Map.of(
                "userId", USER_A.userId(),
                "knowledgeBaseId", USER_A.knowledgeBaseId(),
                "documentId", "warm-document",
                "embeddingModel", EMBEDDING.model(),
                "embeddingDimension", EMBEDDING.dimension(),
                "schemaVersion", EMBEDDING.schemaVersion())));
        stubScan("vector:warm-segment");

        backend.initialize();

        assertThat(backend.search(query(USER_A, vector(1.0f, 0.0f, 0.0f), 1)))
                .extracting(VectorMatch::id).containsExactly("warm-segment");
    }

    @Test
    void repeatedReplacementRebuildsWhenHistoricalNodesReachCapacity() {
        ReflectionTestUtils.setField(backend, "hnswMaxItems", 2);
        stubScan("vector:segment-1");

        backend.upsert(record("segment-1", "doc-a", USER_A, vector(1.0f, 0.0f, 0.0f)));
        backend.upsert(record("segment-1", "doc-a", USER_A, vector(0.0f, 1.0f, 0.0f)));
        backend.upsert(record("segment-1", "doc-a", USER_A, vector(0.0f, 0.0f, 1.0f)));

        List<VectorMatch> matches = backend.search(query(USER_A, vector(0.0f, 0.0f, 1.0f), 1));
        assertThat(matches).extracting(VectorMatch::id).containsExactly("segment-1");
        assertThat(matches.get(0).score()).isEqualTo(1.0f);
    }

    @Test
    void baselineRouterReportsFrozenModeAndBackend() {
        BaselineVectorBackendRouter router = new BaselineVectorBackendRouter(backend);

        assertThat(router.mode()).isEqualTo(VectorStoreMode.BASELINE);
        assertThat(router.type()).isEqualTo(VectorBackendType.LOCAL_REDIS_HNSW);
        assertThat(router.health().available()).isTrue();
    }

    @Test
    void typedSearchEnforcesTenantAndDocumentFilters() {
        backend.upsert(record("a-near", "doc-a", USER_A, vector(1.0f, 0.0f, 0.0f)));
        backend.upsert(record("a-other-doc", "doc-b", USER_A, vector(0.9f, 0.1f, 0.0f)));
        backend.upsert(record("b-nearest", "doc-a", USER_B, vector(0.99f, 0.01f, 0.0f)));

        List<VectorMatch> matches = backend.search(new VectorQuery(
                vector(1.0f, 0.0f, 0.0f), 5,
                new VectorFilter(USER_A, "doc-a", Map.of()), EMBEDDING));

        assertThat(matches).extracting(VectorMatch::id).containsExactly("a-near");
        assertThat(matches.get(0).metadata())
                .containsEntry("userId", "user-a")
                .containsEntry("knowledgeBaseId", "kb-a")
                .containsEntry("documentId", "doc-a");
    }

    @Test
    void repeatedUpsertAndScopedDeleteLeaveNoStaleVersion() {
        backend.upsert(record("segment-1", "doc-a", USER_A, vector(1.0f, 0.0f, 0.0f)));
        backend.upsert(record("segment-1", "doc-a", USER_A, vector(0.0f, 1.0f, 0.0f)));

        List<VectorMatch> overwritten = backend.search(query(USER_A, vector(0.0f, 1.0f, 0.0f), 1));
        assertThat(overwritten).extracting(VectorMatch::id).containsExactly("segment-1");
        assertThat(overwritten.get(0).score()).isEqualTo(1.0f);

        backend.deleteById("segment-1", USER_B);
        assertThat(backend.search(query(USER_A, vector(0.0f, 1.0f, 0.0f), 1))).hasSize(1);

        backend.deleteById("segment-1", USER_A);
        assertThat(backend.search(query(USER_A, vector(0.0f, 1.0f, 0.0f), 1))).isEmpty();
    }

    @Test
    void documentDeleteOnlyRemovesRecordsInsideScope() {
        backend.upsert(record("a-1", "doc-shared", USER_A, vector(1.0f, 0.0f, 0.0f)));
        backend.upsert(record("a-2", "doc-shared", USER_A, vector(0.9f, 0.1f, 0.0f)));
        backend.upsert(record("a-other", "doc-other", USER_A, vector(0.0f, 1.0f, 0.0f)));
        backend.upsert(record("b-1", "doc-shared", USER_B, vector(1.0f, 0.0f, 0.0f)));

        assertThat(backend.deleteByDocument("doc-shared", USER_A)).isEqualTo(2);
        assertThat(backend.search(query(USER_A, vector(1.0f, 0.0f, 0.0f), 10)))
                .extracting(VectorMatch::id).containsExactly("a-other");
        assertThat(backend.search(query(USER_B, vector(1.0f, 0.0f, 0.0f), 10)))
                .extracting(VectorMatch::id).containsExactly("b-1");
    }

    @Test
    void incompatibleEmbeddingSpaceUsesStableErrorCodes() {
        backend.upsert(record("segment-1", "doc-a", USER_A, vector(1.0f, 0.0f, 0.0f)));

        assertThatThrownBy(() -> backend.search(new VectorQuery(
                new float[]{1.0f, 0.0f}, 1,
                new VectorFilter(USER_A, null, Map.of()),
                new EmbeddingSpec(EMBEDDING.model(), 2, EMBEDDING.schemaVersion()))))
                .isInstanceOf(VectorBackendException.class)
                .extracting(error -> ((VectorBackendException) error).getErrorCode())
                .isEqualTo(VectorErrorCode.VECTOR_DIMENSION_MISMATCH);

        assertThatThrownBy(() -> backend.search(new VectorQuery(
                vector(1.0f, 0.0f, 0.0f), 1,
                new VectorFilter(USER_A, null, Map.of()),
                new EmbeddingSpec("other-embedding", 3, 1))))
                .isInstanceOf(VectorBackendException.class)
                .extracting(error -> ((VectorBackendException) error).getErrorCode())
                .isEqualTo(VectorErrorCode.VECTOR_CONFIGURATION_ERROR);
    }

    @Test
    void migrationExportUsesStableIdCursorAndPreservesScope() {
        backend.upsert(record("segment-c", "doc-c", USER_A, vector(0.0f, 0.0f, 1.0f)));
        backend.upsert(record("segment-a", "doc-a", USER_A, vector(1.0f, 0.0f, 0.0f)));
        backend.upsert(record("segment-b", "doc-b", USER_B, vector(0.0f, 1.0f, 0.0f)));
        stubScan("vector:segment-c", "vector:segment-a", "vector:segment-b");

        VectorRecordPage first = backend.exportPage(new VectorExportRequest("", 2, EMBEDDING));

        assertThat(first.complete()).isFalse();
        assertThat(first.nextCursor()).isEqualTo("segment-b");
        assertThat(first.records()).extracting(VectorRecord::id)
                .containsExactly("segment-a", "segment-b");
        assertThat(first.records().get(1).scope()).isEqualTo(USER_B);

        stubScan("vector:segment-b", "vector:segment-c", "vector:segment-a");
        VectorRecordPage second = backend.exportPage(
                new VectorExportRequest(first.nextCursor(), 2, EMBEDDING));

        assertThat(second.complete()).isTrue();
        assertThat(second.nextCursor()).isEmpty();
        assertThat(second.records()).extracting(VectorRecord::id).containsExactly("segment-c");
    }

    private static VectorRecord record(String id, String documentId, VectorScope scope, float[] vector) {
        return new VectorRecord(id, documentId, vector, scope, EMBEDDING, Map.of("segmentIndex", 1));
    }

    private static VectorQuery query(VectorScope scope, float[] vector, int topK) {
        return new VectorQuery(vector, topK, new VectorFilter(scope, null, Map.of()), EMBEDDING);
    }

    @SuppressWarnings("unchecked")
    private void stubScan(String... keys) {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        Cursor<byte[]> cursor = mock(Cursor.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class))).thenReturn(cursor);
        Boolean[] hasNext = new Boolean[keys.length + 1];
        java.util.Arrays.fill(hasNext, 0, keys.length, true);
        hasNext[keys.length] = false;
        when(cursor.hasNext()).thenReturn(hasNext[0], java.util.Arrays.copyOfRange(hasNext, 1, hasNext.length));
        byte[][] encodedKeys = java.util.Arrays.stream(keys)
                .map(key -> key.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
        if (encodedKeys.length > 0) {
            when(cursor.next()).thenReturn(encodedKeys[0],
                    java.util.Arrays.copyOfRange(encodedKeys, 1, encodedKeys.length));
        }
    }

    private static float[] vector(float x, float y, float z) {
        return new float[]{x, y, z};
    }
}
