package com.javaee.aiservice.rag.vector.routing;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultVectorBackendRouterTest {

    private static final EmbeddingSpec EMBEDDING = new EmbeddingSpec("fixture", 2, 1);
    private static final VectorScope SCOPE = new VectorScope("user-a", "kb-a");
    private static final VectorRecord RECORD = new VectorRecord(
            "record-a",
            "document-a",
            new float[]{1.0f, 0.0f},
            SCOPE,
            EMBEDDING,
            Map.of("fixture", true)
    );
    private static final VectorQuery QUERY = new VectorQuery(
            new float[]{1.0f, 0.0f},
            3,
            new VectorFilter(SCOPE, null, Map.of()),
            EMBEDDING
    );

    @Test
    void baselineDelegatesOnlyToLocalAndNeverResolvesQdrant() {
        StubBackend baseline = localBackend(matches("a", "b"));
        StubBackend qdrant = qdrantBackend(matches("q"));
        AtomicInteger qdrantResolutions = new AtomicInteger();
        CapturingRecorder recorder = new CapturingRecorder();

        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                VectorStoreMode.BASELINE,
                baseline,
                () -> {
                    qdrantResolutions.incrementAndGet();
                    return qdrant;
                },
                ShadowRoutingOptions.defaults(),
                recorder
        )) {
            router.upsert(RECORD);
            router.upsertBatch(List.of(RECORD));
            assertThat(router.search(QUERY)).isSameAs(baseline.searchResults);
            router.deleteById(RECORD.id(), SCOPE);
            assertThat(router.deleteByDocument(RECORD.documentId(), SCOPE)).isEqualTo(1L);
            assertThat(router.health()).isSameAs(baseline.health);

            assertThat(router.mode()).isEqualTo(VectorStoreMode.BASELINE);
            assertThat(router.type()).isEqualTo(VectorBackendType.LOCAL_REDIS_HNSW);
            assertThat(qdrantResolutions).hasValue(0);
            assertThat(qdrant.totalCalls()).isZero();
            assertThat(recorder.size()).isZero();
        }
    }

    @Test
    void shadowReturnsTheExactBaselineResultWithoutWaitingForQdrantTimeout() throws Exception {
        StubBackend baseline = localBackend(matches("a", "b"));
        StubBackend qdrant = qdrantBackend(matches("a"));
        CountDownLatch qdrantEntered = new CountDownLatch(1);
        CountDownLatch releaseQdrant = new CountDownLatch(1);
        qdrant.searchEntered = qdrantEntered;
        qdrant.searchRelease = releaseQdrant;
        CapturingRecorder recorder = new CapturingRecorder();

        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                VectorStoreMode.SHADOW,
                baseline,
                () -> qdrant,
                new ShadowRoutingOptions(1.0d, 40L),
                recorder
        )) {
            List<VectorMatch> actual = router.search(QUERY);

            assertThat(actual).isSameAs(baseline.searchResults);
            assertThat(qdrantEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseQdrant.getCount()).isEqualTo(1L);

            ShadowObservation observation = recorder.take();
            assertThat(observation.errorCode()).isEqualTo(VectorErrorCode.VECTOR_TIMEOUT);
            assertThat(observation.baselineIds()).containsExactly("a", "b");
            assertThat(observation.qdrantIds()).isEmpty();
            assertThat(observation.qdrantDurationNanos())
                    .isEqualTo(TimeUnit.MILLISECONDS.toNanos(40L));
        } finally {
            releaseQdrant.countDown();
        }
    }

    @Test
    void shadowComparesLogicalIdsAndAggregatesOverlapAndLatency() throws Exception {
        StubBackend baseline = localBackend(matches("a", "b", "c"));
        StubBackend qdrant = qdrantBackend(matches("b", "c", "d"));
        InMemoryShadowMetricsRecorder metrics = new InMemoryShadowMetricsRecorder();
        CapturingRecorder capture = new CapturingRecorder();
        ShadowMetricsRecorder combined = observation -> {
            metrics.record(observation);
            capture.record(observation);
        };

        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                VectorStoreMode.SHADOW,
                baseline,
                () -> qdrant,
                ShadowRoutingOptions.defaults(),
                combined
        )) {
            assertThat(router.search(QUERY)).isSameAs(baseline.searchResults);

            ShadowObservation observation = capture.take();
            assertThat(observation.errorCode()).isNull();
            assertThat(observation.baselineIds()).containsExactly("a", "b", "c");
            assertThat(observation.qdrantIds()).containsExactly("b", "c", "d");
            assertThat(observation.overlapCount()).isEqualTo(2);
            assertThat(observation.overlapAtK()).isEqualTo(2.0d / 3.0d);

            ShadowMetricsSnapshot snapshot = metrics.snapshot();
            assertThat(snapshot.sampleCount()).isEqualTo(1L);
            assertThat(snapshot.successfulComparisonCount()).isEqualTo(1L);
            assertThat(snapshot.averageOverlapAtK()).isEqualTo(2.0d / 3.0d);
            assertThat(snapshot.errors()).isEmpty();
        }
    }

    @Test
    void zeroSampleRateSkipsShadowQueriesButStillDualWrites() throws Exception {
        StubBackend baseline = localBackend(matches("a"));
        StubBackend qdrant = qdrantBackend(matches("q"));
        AtomicInteger qdrantResolutions = new AtomicInteger();
        CapturingRecorder recorder = new CapturingRecorder();

        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                VectorStoreMode.SHADOW,
                baseline,
                () -> {
                    qdrantResolutions.incrementAndGet();
                    return qdrant;
                },
                new ShadowRoutingOptions(0.0d, 100L),
                recorder
        )) {
            assertThat(router.search(QUERY)).isSameAs(baseline.searchResults);
            assertThat(qdrantResolutions).hasValue(0);
            assertThat(recorder.size()).isZero();

            router.upsert(RECORD);
            ShadowObservation observation = recorder.take();
            assertThat(observation.operation()).isEqualTo(ShadowOperation.UPSERT);
            assertThat(observation.logicalIds()).containsExactly(RECORD.id());
            assertThat(observation.errorCode()).isNull();
            assertThat(baseline.upserts).hasValue(1);
            assertThat(qdrant.upserts).hasValue(1);
            assertThat(qdrantResolutions).hasValue(1);
        }
    }

    @Test
    void shadowQdrantFailureIsObservableButCannotChangePrimaryResult() throws Exception {
        StubBackend baseline = localBackend(matches("a", "b"));
        StubBackend qdrant = qdrantBackend(matches("q"));
        qdrant.searchFailure = new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_AUTH_FAILED,
                "sanitized fixture failure",
                false
        );
        CapturingRecorder recorder = new CapturingRecorder();

        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                VectorStoreMode.SHADOW,
                baseline,
                () -> qdrant,
                ShadowRoutingOptions.defaults(),
                recorder
        )) {
            assertThat(router.search(QUERY)).isSameAs(baseline.searchResults);
            assertThat(recorder.take().errorCode()).isEqualTo(VectorErrorCode.VECTOR_AUTH_FAILED);
        }
    }

    @Test
    void shadowWriteFailureDoesNotEscapeTheBaselineCall() throws Exception {
        StubBackend baseline = localBackend(matches("a"));
        StubBackend qdrant = qdrantBackend(matches("q"));
        qdrant.upsertFailure = new IllegalStateException("fixture-token-that-must-not-be-recorded");
        CapturingRecorder recorder = new CapturingRecorder();

        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                VectorStoreMode.SHADOW,
                baseline,
                () -> qdrant,
                ShadowRoutingOptions.defaults(),
                recorder
        )) {
            router.upsert(RECORD);
            ShadowObservation observation = recorder.take();

            assertThat(baseline.upserts).hasValue(1);
            assertThat(observation.errorCode()).isEqualTo(VectorErrorCode.VECTOR_UPSERT_FAILED);
            assertThat(observation.toString()).doesNotContain("fixture-token-that-must-not-be-recorded");
            assertThat(observation.toString()).doesNotContain("1.0");
            assertThat(observation.toString()).doesNotContain("fixture=true");
        }
    }

    @Test
    void qdrantModeNeverFallsBackToBaseline() {
        StubBackend baseline = localBackend(matches("baseline"));
        StubBackend qdrant = qdrantBackend(matches("qdrant"));
        qdrant.searchFailure = new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_TIMEOUT,
                "fixture timeout",
                true
        );

        try (DefaultVectorBackendRouter router = new DefaultVectorBackendRouter(
                VectorStoreMode.QDRANT,
                baseline,
                () -> qdrant,
                ShadowRoutingOptions.defaults(),
                observation -> {
                }
        )) {
            assertThat(router.mode()).isEqualTo(VectorStoreMode.QDRANT);
            assertThat(router.type()).isEqualTo(VectorBackendType.QDRANT);
            assertThatThrownBy(() -> router.search(QUERY))
                    .isInstanceOf(VectorBackendException.class)
                    .extracting(exception -> ((VectorBackendException) exception).getErrorCode())
                    .isEqualTo(VectorErrorCode.VECTOR_TIMEOUT);
            assertThat(baseline.searches).hasValue(0);
            assertThat(qdrant.searches).hasValue(1);
        }
    }

    @Test
    void shadowOptionsRejectInvalidSamplingAndTimeout() {
        assertThatThrownBy(() -> new ShadowRoutingOptions(-0.01d, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShadowRoutingOptions(1.01d, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShadowRoutingOptions(0.5d, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ShadowRoutingOptions.defaults())
                .isEqualTo(new ShadowRoutingOptions(1.0d, 3_000L));
    }

    private static StubBackend localBackend(List<VectorMatch> matches) {
        return new StubBackend(VectorBackendType.LOCAL_REDIS_HNSW, matches);
    }

    private static StubBackend qdrantBackend(List<VectorMatch> matches) {
        return new StubBackend(VectorBackendType.QDRANT, matches);
    }

    private static List<VectorMatch> matches(String... ids) {
        return java.util.stream.IntStream.range(0, ids.length)
                .mapToObj(index -> new VectorMatch(
                        ids[index],
                        "doc-" + ids[index],
                        1.0f - (index * 0.1f),
                        Map.of()
                ))
                .toList();
    }

    private static final class CapturingRecorder implements ShadowMetricsRecorder {

        private final BlockingQueue<ShadowObservation> observations = new LinkedBlockingQueue<>();

        @Override
        public void record(ShadowObservation observation) {
            observations.add(observation);
        }

        ShadowObservation take() throws InterruptedException {
            ShadowObservation observation = observations.poll(2, TimeUnit.SECONDS);
            assertThat(observation).as("shadow observation").isNotNull();
            return observation;
        }

        int size() {
            return observations.size();
        }
    }

    private static final class StubBackend implements VectorBackend {

        private final VectorBackendType type;
        private final List<VectorMatch> searchResults;
        private final VectorBackendHealth health;
        private final AtomicInteger upserts = new AtomicInteger();
        private final AtomicInteger searches = new AtomicInteger();
        private final AtomicInteger deletesById = new AtomicInteger();
        private final AtomicInteger deletesByDocument = new AtomicInteger();

        private volatile RuntimeException searchFailure;
        private volatile RuntimeException upsertFailure;
        private volatile CountDownLatch searchEntered;
        private volatile CountDownLatch searchRelease;

        private StubBackend(VectorBackendType type, List<VectorMatch> searchResults) {
            this.type = type;
            this.searchResults = searchResults;
            this.health = new VectorBackendHealth(type, true, "fixture", Map.of());
        }

        @Override
        public VectorBackendType type() {
            return type;
        }

        @Override
        public void upsert(VectorRecord record) {
            upserts.incrementAndGet();
            if (upsertFailure != null) {
                throw upsertFailure;
            }
        }

        @Override
        public List<VectorMatch> search(VectorQuery query) {
            searches.incrementAndGet();
            if (searchEntered != null) {
                searchEntered.countDown();
            }
            if (searchRelease != null) {
                try {
                    searchRelease.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture interrupted", exception);
                }
            }
            if (searchFailure != null) {
                throw searchFailure;
            }
            return searchResults;
        }

        @Override
        public void deleteById(String id, VectorScope scope) {
            deletesById.incrementAndGet();
        }

        @Override
        public long deleteByDocument(String documentId, VectorScope scope) {
            deletesByDocument.incrementAndGet();
            return 1L;
        }

        @Override
        public VectorBackendHealth health() {
            return health;
        }

        int totalCalls() {
            return upserts.get() + searches.get() + deletesById.get() + deletesByDocument.get();
        }
    }
}
