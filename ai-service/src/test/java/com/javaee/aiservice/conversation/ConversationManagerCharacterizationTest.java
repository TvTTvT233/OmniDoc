package com.javaee.aiservice.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationManagerCharacterizationTest {

    private ConversationManager manager;
    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = new ConversationManager();
        redisTemplate = mock(RedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        doReturn(hashOperations).when(redisTemplate).opsForHash();
        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(manager, "maxMessages", 100);
        ReflectionTestUtils.setField(manager, "expiryHours", 24);
    }

    @Test
    void createConversationStoresRedisHashAndAppliesSlidingTwentyFourHourExpiry() {
        String conversationId = manager.createConversation("user-1");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> fields =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("conv:" + conversationId), fields.capture());
        verify(redisTemplate).expire("conv:" + conversationId, Duration.ofHours(24));

        assertThat(fields.getValue())
                .containsEntry("userId", "user-1")
                .containsKeys("createdAt", "updatedAt", "messages");
        assertThat(fields.getValue().get("messages")).isEqualTo(List.of());
    }

    @Test
    void appendStoresFlatPrefixedStringsAndCountTruncationCanSplitATurn() {
        ReflectionTestUtils.setField(manager, "maxMessages", 3);
        when(hashOperations.get("conv:conv-1", "messages")).thenReturn(new ArrayList<>(List.of(
                "User: old question",
                "Assistant: old answer"
        )));

        manager.addMessage("conv-1", "new question", "new answer");

        org.mockito.ArgumentCaptor<Object> messages = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(hashOperations).put(eq("conv:conv-1"), eq("messages"), messages.capture());
        assertThat(asStringList(messages.getValue())).containsExactly(
                "Assistant: old answer",
                "User: new question",
                "Assistant: new answer"
        );
        verify(redisTemplate).expire("conv:conv-1", Duration.ofHours(24));
    }

    @Test
    void longMessageIsKeptInFullBecauseTheLimitCountsEntriesRatherThanTokens() {
        ReflectionTestUtils.setField(manager, "maxMessages", 2);
        when(hashOperations.get("conv:conv-long", "messages")).thenReturn(new ArrayList<>());
        String longMessage = "长".repeat(20_000);

        manager.addMessage("conv-long", longMessage, "ok");

        org.mockito.ArgumentCaptor<Object> messages = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(hashOperations).put(eq("conv:conv-long"), eq("messages"), messages.capture());
        assertThat(asStringList(messages.getValue()).get(0))
                .startsWith("User: ")
                .hasSize(longMessage.length() + "User: ".length());
    }

    @Test
    void concurrentAppendsReadTheSameSnapshotAndEachRewriteOmitsTheOtherTurn() throws Exception {
        List<String> baseline = List.of("User: base", "Assistant: base");
        CountDownLatch bothReadsStarted = new CountDownLatch(2);
        List<List<String>> writes = Collections.synchronizedList(new ArrayList<>());
        when(hashOperations.get("conv:conv-race", "messages")).thenAnswer(invocation -> {
            bothReadsStarted.countDown();
            if (!bothReadsStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("two append operations did not reach the same read snapshot");
            }
            return new ArrayList<>(baseline);
        });
        doAnswer(invocation -> {
            writes.add(new ArrayList<>(asStringList(invocation.getArgument(2))));
            return null;
        }).when(hashOperations).put(eq("conv:conv-race"), eq("messages"), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> manager.addMessage("conv-race", "question-a", "answer-a"));
            Future<?> second = executor.submit(() -> manager.addMessage("conv-race", "question-b", "answer-b"));
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(writes).hasSize(2);
        assertThat(writes.stream().anyMatch(write -> write.contains("User: question-a")
                && !write.contains("User: question-b"))).isTrue();
        assertThat(writes.stream().anyMatch(write -> write.contains("User: question-b")
                && !write.contains("User: question-a"))).isTrue();
        List<String> lastWrite = writes.get(writes.size() - 1);
        assertThat(lastWrite.contains("User: question-a") ^ lastWrite.contains("User: question-b")).isTrue();
        verify(redisTemplate, times(2)).expire("conv:conv-race", Duration.ofHours(24));
    }

    @Test
    void ownerCheckRejectsForeignUserWhileDeleteOnlyTargetsConversationKey() {
        when(hashOperations.get("conv:conv-1", "userId")).thenReturn("owner");

        assertThatThrownBy(() -> manager.assertOwner("conv-1", "other"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("无权访问");

        manager.deleteConversation("conv-1");

        verify(redisTemplate).delete("conv:conv-1");
        verify(redisTemplate, never()).delete("ctx:conv-1");
        verify(redisTemplate, never()).expire(anyString(), eq(Duration.ZERO));
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        return (List<String>) value;
    }
}
