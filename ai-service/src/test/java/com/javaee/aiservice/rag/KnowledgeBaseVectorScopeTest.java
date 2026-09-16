package com.javaee.aiservice.rag;

import com.javaee.aiservice.rag.vector.contract.VectorScope;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseVectorScopeTest {

    @Test
    @SuppressWarnings("unchecked")
    void removeDocumentUsesOneScopedVectorDelete() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.get("doc_segments:doc-1")).thenReturn(List.of("segment-1", "segment-2"));
        when(hashes.entries("doc:doc-1")).thenReturn(Map.of(
                "userId", "user-1",
                "knowledgeBaseId", "kb-1"));
        ReflectionTestUtils.setField(knowledgeBase, "redisTemplate", redis);
        ReflectionTestUtils.setField(knowledgeBase, "vectorStore", vectorStore);

        knowledgeBase.removeDocument("doc-1");

        verify(vectorStore).deleteByDocument("doc-1", new VectorScope("user-1", "kb-1"));
        verify(vectorStore, never()).delete("segment-1");
        verify(vectorStore, never()).delete("segment-2");
    }
}
