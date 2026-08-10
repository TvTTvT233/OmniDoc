package com.javaee.aiservice.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextManagerCharacterizationTest {

    private ContextManager manager;
    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = new ContextManager();
        redisTemplate = mock(RedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        doReturn(hashOperations).when(redisTemplate).opsForHash();
        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
    }

    @Test
    void updateAcceptsUnrestrictedFieldsAndDoesNotApplyExpiry() {
        Map<String, Object> unrestricted = Map.of(
                "documentContent", "full document body",
                "lastToolResults", List.of(Map.of("secret", "unbounded-result")),
                "approvalToken", "legacy-token"
        );

        manager.updateContext("conv-1", unrestricted);

        verify(hashOperations).putAll("ctx:conv-1", unrestricted);
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void mergeReadsDeletesAndRewritesTheWholeHashWithoutExpiry() {
        Map<Object, Object> existing = new HashMap<>();
        existing.put("documentId", "old-doc");
        when(hashOperations.entries("ctx:conv-1")).thenReturn(existing);

        manager.mergeContext("conv-1", Map.of("selectedText", "selection"));

        org.mockito.InOrder order = inOrder(hashOperations, redisTemplate);
        order.verify(hashOperations).entries("ctx:conv-1");
        order.verify(redisTemplate).delete("ctx:conv-1");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> rewritten =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        order.verify(hashOperations).putAll(eq("ctx:conv-1"), rewritten.capture());
        assertThat(rewritten.getValue())
                .containsEntry("documentId", "old-doc")
                .containsEntry("selectedText", "selection");
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void concurrentMergesFromTheSameSnapshotEachOmitTheOtherUpdate() throws Exception {
        Map<Object, Object> baseline = Map.of("documentId", "doc-1");
        CountDownLatch bothReadsStarted = new CountDownLatch(2);
        List<Map<String, Object>> writes = Collections.synchronizedList(new ArrayList<>());
        when(hashOperations.entries("ctx:conv-race")).thenAnswer(invocation -> {
            bothReadsStarted.countDown();
            if (!bothReadsStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("two merge operations did not reach the same read snapshot");
            }
            return new HashMap<>(baseline);
        });
        doAnswer(invocation -> {
            writes.add(new HashMap<>(asStringObjectMap(invocation.getArgument(1))));
            return null;
        }).when(hashOperations).putAll(eq("ctx:conv-race"), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> manager.mergeContext("conv-race", Map.of("selectedText", "a")));
            Future<?> second = executor.submit(() -> manager.mergeContext("conv-race", Map.of("route", "/b")));
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(writes).hasSize(2);
        assertThat(writes.stream().anyMatch(write -> write.containsKey("selectedText")
                && !write.containsKey("route"))).isTrue();
        assertThat(writes.stream().anyMatch(write -> write.containsKey("route")
                && !write.containsKey("selectedText"))).isTrue();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void clearContextOnlyDeletesTheRuntimeHashKey() {
        manager.clearContext("conv-1");

        verify(redisTemplate).delete("ctx:conv-1");
        verify(redisTemplate, never()).delete("conv:conv-1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }
}
