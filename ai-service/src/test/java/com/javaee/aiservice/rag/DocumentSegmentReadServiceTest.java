package com.javaee.aiservice.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentSegmentReadServiceTest {
    RedisTemplate<String,Object> redis;
    HashOperations<String,Object,Object> hashes;
    ValueOperations<String,Object> values;
    DocumentSegmentReadService service;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        redis = mock(RedisTemplate.class);
        hashes = mock(HashOperations.class);
        values = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(SessionCallback.class))).thenAnswer(call ->
                ((SessionCallback<?>)call.getArgument(0)).execute(redis));
        when(redis.exec()).thenReturn(List.of());
        when(hashes.entries("doc:d")).thenReturn(Map.of("userId","1"));
        when(values.get("doc_segments:d")).thenReturn(List.of("d_seg_0"));
        when(values.get("segment:d_seg_0")).thenReturn("synthetic text");
        service = new DocumentSegmentReadService(redis);
    }
    @Test void missingAllIndexRecordsIsNotIndexed() {
        when(hashes.entries("doc:d")).thenReturn(Map.of());
        when(values.get("doc_segments:d")).thenReturn(null);
        assertThat(service.read("d","1").indexState()).isEqualTo("NOT_INDEXED");
    }
    @Test void completeSegmentsAreAvailableWithoutCertifyingVectors() {
        assertThat(service.read("d","1").segments()).containsExactly(Map.of("segmentId","d_seg_0","content","synthetic text"));
        verify(redis).watch(List.of("segment:d_seg_0"));
    }
    @Test void everyExistingStrategyIdIsSupported() {
        for(String suffix : List.of("chapter_0", "semantic_1")) {
            when(values.get("doc_segments:d")).thenReturn(List.of("d_"+suffix));
            when(values.get("segment:d_"+suffix)).thenReturn("synthetic");
            assertThat(service.read("d","1").indexState()).isEqualTo("SEGMENTS_AVAILABLE");
        }
    }
    @Test void differentIndexOwnerIsDeniedBeforeReadingContent() {
        assertStatus(403, () -> service.read("d","2"));
        verify(values, never()).get("segment:d_seg_0");
    }
    @Test void orphanedRecordsAreNotReportedAsNotIndexed() {
        when(hashes.entries("doc:d")).thenReturn(Map.of());
        assertStatus(409, () -> service.read("d","1"));
        when(values.get("doc_segments:d")).thenReturn(null);
        when(redis.hasKey("content:d")).thenReturn(true);
        assertStatus(409, () -> service.read("d","1"));
    }
    @Test void missingOwnerOrListOrContentIsExplicitlyIncomplete() {
        when(hashes.entries("doc:d")).thenReturn(Map.of("strategy","AUTO"));
        assertStatus(409, () -> service.read("d","1"));
        when(hashes.entries("doc:d")).thenReturn(Map.of("userId","1"));
        for(Object ids : Arrays.asList(null, List.of(), "bad-type", List.of("other_seg_0"), List.of("d_seg_0","d_seg_0"))) {
            when(values.get("doc_segments:d")).thenReturn(ids);
            assertStatus(409, () -> service.read("d","1"));
        }
        when(values.get("doc_segments:d")).thenReturn(List.of("d_seg_0"));
        when(values.get("segment:d_seg_0")).thenReturn(null);
        assertStatus(409, () -> service.read("d","1"));
    }
    @Test void redisFailureAtMetadataIsUnavailable() {
        when(hashes.entries("doc:d")).thenThrow(new IllegalStateException("internal connection"));
        assertStatus(503, () -> service.read("d","1"));
    }
    @Test void redisFailureAtSegmentListIsUnavailable() {
        when(values.get("doc_segments:d")).thenThrow(new IllegalStateException("internal connection"));
        assertStatus(503, () -> service.read("d","1"));
    }
    @Test void redisFailureAtSegmentContentIsUnavailable() {
        when(values.get("segment:d_seg_0")).thenThrow(new IllegalStateException("internal connection"));
        assertStatus(503, () -> service.read("d","1"));
    }
    @Test void changedSnapshotIsNotReturned() {
        when(redis.exec()).thenReturn(null);
        assertStatus(409, () -> service.read("d","1"));
    }
    static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SegmentReadException.class,
                exception -> assertThat(exception.status()).isEqualTo(status));
    }
}
