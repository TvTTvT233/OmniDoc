package com.javaee.aiservice.rag;

import com.javaee.aiservice.rag.vector.local.BaselineVectorBackendRouter;
import com.javaee.aiservice.rag.vector.local.LocalRedisHnswVectorBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Freezes the current local Redis + in-memory HNSW behavior before QV01 extracts an adapter.
 */
class VectorStoreBaselineCharacterizationTest {

    private final Map<String, Map<Object, Object>> metadata = new LinkedHashMap<>();
    private VectorStore vectorStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<Object, Object> values = new LinkedHashMap<>();
            Map<?, ?> supplied = invocation.getArgument(1);
            supplied.forEach(values::put);
            metadata.put(key, values);
            return null;
        }).when(hashOperations).putAll(anyString(), anyMap());
        when(hashOperations.entries(anyString())).thenAnswer(invocation ->
                metadata.getOrDefault(invocation.getArgument(0), Map.of()));

        LocalRedisHnswVectorBackend backend = new LocalRedisHnswVectorBackend(redisTemplate);
        ReflectionTestUtils.setField(backend, "defaultEmbeddingModel", "fixture-embedding");
        ReflectionTestUtils.setField(backend, "defaultSchemaVersion", 1);
        ReflectionTestUtils.setField(backend, "hnswM", 8);
        ReflectionTestUtils.setField(backend, "hnswEfConstruction", 32);
        ReflectionTestUtils.setField(backend, "hnswEf", 16);
        ReflectionTestUtils.setField(backend, "hnswMaxItems", 64);

        vectorStore = new VectorStore(new BaselineVectorBackendRouter(backend));
        ReflectionTestUtils.setField(vectorStore, "defaultEmbeddingModel", "fixture-embedding");
        ReflectionTestUtils.setField(vectorStore, "defaultSchemaVersion", 1);
    }

    @Test
    void storesSearchesAndPostFiltersByTenantMetadata() {
        vectorStore.store("user-a-near", vector(1.0f, 0.0f, 0.0f), metadata("user-a", "kb-a", "doc-a"));
        vectorStore.store("user-a-far", vector(0.0f, 1.0f, 0.0f), metadata("user-a", "kb-a", "doc-b"));
        vectorStore.store("user-b-nearest", vector(0.99f, 0.01f, 0.0f), metadata("user-b", "kb-a", "doc-c"));

        List<Map<String, Object>> results = vectorStore.search(
                vector(1.0f, 0.0f, 0.0f), 2, Map.of("userId", "user-a", "knowledgeBaseId", "kb-a"));

        assertThat(results).extracting(result -> result.get("id"))
                .containsExactly("user-a-near", "user-a-far");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.get("userId")).isEqualTo("user-a");
            assertThat(result.get("knowledgeBaseId")).isEqualTo("kb-a");
            assertThat(result.get("embeddingModel")).isEqualTo("fixture-embedding");
            assertThat(result.get("embeddingDimension")).isEqualTo(3);
            assertThat(result.get("schemaVersion")).isEqualTo(1);
        });
    }

    @Test
    void overwritingExistingItemMustReplaceItsVector() {
        vectorStore.store("segment-1", vector(1.0f, 0.0f, 0.0f), metadata("user-a", "kb-a", "doc-a"));
        vectorStore.store("segment-1", vector(0.0f, 1.0f, 0.0f), metadata("user-a", "kb-a", "doc-a"));

        List<Map<String, Object>> results = vectorStore.search(vector(0.0f, 1.0f, 0.0f), 1);
        assertThat(results).extracting(result -> result.get("id")).containsExactly("segment-1");
        assertThat(results.get(0).get("similarity")).isEqualTo(1.0f);
    }

    @Test
    void deletingOverwrittenItemMustNotLeaveAStaleIndexHit() {
        vectorStore.store("segment-1", vector(1.0f, 0.0f, 0.0f), metadata("user-a", "kb-a", "doc-a"));
        vectorStore.store("segment-1", vector(0.0f, 1.0f, 0.0f), metadata("user-a", "kb-a", "doc-a"));

        vectorStore.delete("segment-1");

        assertThat(vectorStore.search(vector(0.0f, 1.0f, 0.0f), 1)).isEmpty();
    }

    private static float[] vector(float x, float y, float z) {
        return new float[]{x, y, z};
    }

    private static Map<String, Object> metadata(String userId, String knowledgeBaseId, String documentId) {
        return Map.of(
                "userId", userId,
                "knowledgeBaseId", knowledgeBaseId,
                "documentId", documentId);
    }
}
