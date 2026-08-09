package com.javaee.aiservice.rag.vector.routing;

import com.javaee.aiservice.rag.vector.contract.VectorBackend;
import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendRouter;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Three-mode vector router. Qdrant is resolved lazily so baseline mode does not create a client.
 */
public final class DefaultVectorBackendRouter implements VectorBackendRouter, AutoCloseable {

    private static final int SHADOW_QUEUE_CAPACITY = 256;

    private final VectorStoreMode mode;
    private final VectorBackend baseline;
    private final Supplier<VectorBackend> qdrantProvider;
    private final ShadowRoutingOptions shadowOptions;
    private final ShadowMetricsRecorder metrics;
    private final ExecutorService shadowExecutor;
    private final ScheduledExecutorService timeoutScheduler;
    private final DoubleSupplier random;
    private final LongSupplier nanoTime;
    private final boolean ownsExecutors;

    private volatile VectorBackend resolvedQdrant;

    public DefaultVectorBackendRouter(
            VectorStoreMode mode,
            VectorBackend baseline,
            Supplier<VectorBackend> qdrantProvider,
            ShadowRoutingOptions shadowOptions,
            ShadowMetricsRecorder metrics
    ) {
        this(
                mode,
                baseline,
                qdrantProvider,
                shadowOptions,
                metrics,
                newShadowExecutor(),
                newTimeoutScheduler(),
                () -> ThreadLocalRandom.current().nextDouble(),
                System::nanoTime,
                true
        );
    }

    public DefaultVectorBackendRouter(
            VectorStoreMode mode,
            VectorBackend baseline,
            Supplier<VectorBackend> qdrantProvider,
            ShadowRoutingOptions shadowOptions,
            ShadowMetricsRecorder metrics,
            ExecutorService shadowExecutor,
            ScheduledExecutorService timeoutScheduler,
            DoubleSupplier random,
            LongSupplier nanoTime
    ) {
        this(
                mode,
                baseline,
                qdrantProvider,
                shadowOptions,
                metrics,
                shadowExecutor,
                timeoutScheduler,
                random,
                nanoTime,
                false
        );
    }

    private DefaultVectorBackendRouter(
            VectorStoreMode mode,
            VectorBackend baseline,
            Supplier<VectorBackend> qdrantProvider,
            ShadowRoutingOptions shadowOptions,
            ShadowMetricsRecorder metrics,
            ExecutorService shadowExecutor,
            ScheduledExecutorService timeoutScheduler,
            DoubleSupplier random,
            LongSupplier nanoTime,
            boolean ownsExecutors
    ) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.baseline = Objects.requireNonNull(baseline, "baseline must not be null");
        this.qdrantProvider = Objects.requireNonNull(qdrantProvider, "qdrantProvider must not be null");
        this.shadowOptions = Objects.requireNonNull(shadowOptions, "shadowOptions must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.shadowExecutor = Objects.requireNonNull(shadowExecutor, "shadowExecutor must not be null");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        this.ownsExecutors = ownsExecutors;
        if (baseline.type() != VectorBackendType.LOCAL_REDIS_HNSW) {
            throw configurationError("Baseline backend must be local-redis-hnsw", null);
        }
    }

    @Override
    public VectorStoreMode mode() {
        return mode;
    }

    @Override
    public VectorBackendType type() {
        return mode == VectorStoreMode.QDRANT
                ? VectorBackendType.QDRANT
                : VectorBackendType.LOCAL_REDIS_HNSW;
    }

    @Override
    public void upsert(VectorRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        switch (mode) {
            case BASELINE -> baseline.upsert(record);
            case SHADOW -> {
                long started = nanoTime.getAsLong();
                baseline.upsert(record);
                long baselineDuration = elapsed(started);
                submitShadowOperation(
                        ShadowOperation.UPSERT,
                        List.of(record.id()),
                        baselineDuration,
                        () -> qdrantUpsert(record),
                        VectorErrorCode.VECTOR_UPSERT_FAILED
                );
            }
            case QDRANT -> qdrantUpsert(record);
        }
    }

    @Override
    public void upsertBatch(List<VectorRecord> records) {
        if (records == null) {
            throw new IllegalArgumentException("records must not be null");
        }
        List<VectorRecord> copy = List.copyOf(records);
        switch (mode) {
            case BASELINE -> baseline.upsertBatch(copy);
            case SHADOW -> {
                long started = nanoTime.getAsLong();
                baseline.upsertBatch(copy);
                long baselineDuration = elapsed(started);
                submitShadowOperation(
                        ShadowOperation.UPSERT_BATCH,
                        copy.stream().map(VectorRecord::id).toList(),
                        baselineDuration,
                        () -> qdrantUpsertBatch(copy),
                        VectorErrorCode.VECTOR_UPSERT_FAILED
                );
            }
            case QDRANT -> qdrantUpsertBatch(copy);
        }
    }

    @Override
    public List<VectorMatch> search(VectorQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return switch (mode) {
            case BASELINE -> baseline.search(query);
            case QDRANT -> qdrantSearch(query);
            case SHADOW -> shadowSearch(query);
        };
    }

    @Override
    public void deleteById(String id, VectorScope scope) {
        switch (mode) {
            case BASELINE -> baseline.deleteById(id, scope);
            case SHADOW -> {
                long started = nanoTime.getAsLong();
                baseline.deleteById(id, scope);
                long baselineDuration = elapsed(started);
                submitShadowOperation(
                        ShadowOperation.DELETE_BY_ID,
                        id == null || id.isBlank() ? List.of() : List.of(id),
                        baselineDuration,
                        () -> qdrantDeleteById(id, scope),
                        VectorErrorCode.VECTOR_DELETE_FAILED
                );
            }
            case QDRANT -> qdrantDeleteById(id, scope);
        }
    }

    @Override
    public long deleteByDocument(String documentId, VectorScope scope) {
        return switch (mode) {
            case BASELINE -> baseline.deleteByDocument(documentId, scope);
            case QDRANT -> qdrantDeleteByDocument(documentId, scope);
            case SHADOW -> {
                long started = nanoTime.getAsLong();
                long deleted = baseline.deleteByDocument(documentId, scope);
                long baselineDuration = elapsed(started);
                submitShadowOperation(
                        ShadowOperation.DELETE_BY_DOCUMENT,
                        List.of(),
                        baselineDuration,
                        () -> qdrantDeleteByDocument(documentId, scope),
                        VectorErrorCode.VECTOR_DELETE_FAILED
                );
                yield deleted;
            }
        };
    }

    @Override
    public VectorBackendHealth health() {
        return switch (mode) {
            case BASELINE, SHADOW -> baseline.health();
            case QDRANT -> qdrantCall(
                    () -> qdrant().health(),
                    VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE,
                    "Qdrant health check failed"
            );
        };
    }

    private List<VectorMatch> shadowSearch(VectorQuery query) {
        if (!sampled()) {
            return baseline.search(query);
        }

        ShadowSearchState state = new ShadowSearchState(query.topK());
        startShadowSearch(query, state);

        long baselineStarted = nanoTime.getAsLong();
        try {
            List<VectorMatch> baselineMatches = baseline.search(query);
            state.baselineSucceeded(ids(baselineMatches, query.topK()), elapsed(baselineStarted));
            return baselineMatches;
        } catch (RuntimeException exception) {
            state.baselineFailed(errorCode(exception, VectorErrorCode.VECTOR_SEARCH_FAILED), elapsed(baselineStarted));
            throw exception;
        }
    }

    private void startShadowSearch(VectorQuery query, ShadowSearchState state) {
        final Future<?> task;
        try {
            task = shadowExecutor.submit(() -> {
                long qdrantStarted = nanoTime.getAsLong();
                try {
                    List<VectorMatch> qdrantMatches = qdrantSearch(query);
                    state.qdrantSucceeded(ids(qdrantMatches, query.topK()), elapsed(qdrantStarted));
                } catch (RuntimeException exception) {
                    state.qdrantFailed(
                            errorCode(exception, VectorErrorCode.VECTOR_SEARCH_FAILED),
                            elapsed(qdrantStarted)
                    );
                }
            });
        } catch (RejectedExecutionException exception) {
            state.qdrantFailed(VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE, 0L);
            return;
        }

        try {
            ScheduledFuture<?> timeout = timeoutScheduler.schedule(
                    () -> {
                        if (state.qdrantTimedOut(TimeUnit.MILLISECONDS.toNanos(shadowOptions.timeoutMillis()))) {
                            task.cancel(true);
                        }
                    },
                    shadowOptions.timeoutMillis(),
                    TimeUnit.MILLISECONDS
            );
            state.timeoutFuture(timeout);
        } catch (RejectedExecutionException exception) {
            if (state.qdrantFailed(VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE, 0L)) {
                task.cancel(true);
            }
        }
    }

    private void submitShadowOperation(
            ShadowOperation operation,
            List<String> logicalIds,
            long baselineDuration,
            Runnable qdrantCall,
            VectorErrorCode fallbackError
    ) {
        AtomicBoolean recorded = new AtomicBoolean();
        final Future<?> task;
        try {
            task = shadowExecutor.submit(() -> {
                long qdrantStarted = nanoTime.getAsLong();
                try {
                    qdrantCall.run();
                    recordOperationOnce(
                            recorded,
                            operation,
                            logicalIds,
                            baselineDuration,
                            elapsed(qdrantStarted),
                            null
                    );
                } catch (RuntimeException exception) {
                    recordOperationOnce(
                            recorded,
                            operation,
                            logicalIds,
                            baselineDuration,
                            elapsed(qdrantStarted),
                            errorCode(exception, fallbackError)
                    );
                }
            });
        } catch (RejectedExecutionException exception) {
            recordOperationOnce(
                    recorded,
                    operation,
                    logicalIds,
                    baselineDuration,
                    0L,
                    VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE
            );
            return;
        }

        try {
            timeoutScheduler.schedule(
                    () -> {
                        if (recordOperationOnce(
                                recorded,
                                operation,
                                logicalIds,
                                baselineDuration,
                                TimeUnit.MILLISECONDS.toNanos(shadowOptions.timeoutMillis()),
                                VectorErrorCode.VECTOR_TIMEOUT
                        )) {
                            task.cancel(true);
                        }
                    },
                    shadowOptions.timeoutMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException exception) {
            if (recordOperationOnce(
                    recorded,
                    operation,
                    logicalIds,
                    baselineDuration,
                    0L,
                    VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE
            )) {
                task.cancel(true);
            }
        }
    }

    private boolean recordOperationOnce(
            AtomicBoolean recorded,
            ShadowOperation operation,
            List<String> logicalIds,
            long baselineDuration,
            long qdrantDuration,
            VectorErrorCode errorCode
    ) {
        if (!recorded.compareAndSet(false, true)) {
            return false;
        }
        metrics.record(ShadowObservation.operation(
                operation,
                logicalIds,
                baselineDuration,
                qdrantDuration,
                errorCode
        ));
        return true;
    }

    private boolean sampled() {
        double sampleRate = shadowOptions.sampleRate();
        return sampleRate >= 1.0d || (sampleRate > 0.0d && random.getAsDouble() < sampleRate);
    }

    private void qdrantUpsert(VectorRecord record) {
        qdrantRun(
                () -> qdrant().upsert(record),
                VectorErrorCode.VECTOR_UPSERT_FAILED,
                "Qdrant upsert failed"
        );
    }

    private void qdrantUpsertBatch(List<VectorRecord> records) {
        qdrantRun(
                () -> qdrant().upsertBatch(records),
                VectorErrorCode.VECTOR_UPSERT_FAILED,
                "Qdrant batch upsert failed"
        );
    }

    private List<VectorMatch> qdrantSearch(VectorQuery query) {
        List<VectorMatch> matches = qdrantCall(
                () -> qdrant().search(query),
                VectorErrorCode.VECTOR_SEARCH_FAILED,
                "Qdrant search failed"
        );
        if (matches == null) {
            throw new VectorBackendException(
                    VectorBackendType.QDRANT,
                    VectorErrorCode.VECTOR_SEARCH_FAILED,
                    "Qdrant search returned no result list",
                    false
            );
        }
        return matches;
    }

    private void qdrantDeleteById(String id, VectorScope scope) {
        qdrantRun(
                () -> qdrant().deleteById(id, scope),
                VectorErrorCode.VECTOR_DELETE_FAILED,
                "Qdrant delete by ID failed"
        );
    }

    private long qdrantDeleteByDocument(String documentId, VectorScope scope) {
        return qdrantCall(
                () -> qdrant().deleteByDocument(documentId, scope),
                VectorErrorCode.VECTOR_DELETE_FAILED,
                "Qdrant delete by document failed"
        );
    }

    private VectorBackend qdrant() {
        VectorBackend backend = resolvedQdrant;
        if (backend != null) {
            return backend;
        }
        synchronized (this) {
            backend = resolvedQdrant;
            if (backend == null) {
                try {
                    backend = qdrantProvider.get();
                    if (backend == null) {
                        throw configurationError("Qdrant backend is not configured", null);
                    }
                    if (backend.type() != VectorBackendType.QDRANT) {
                        throw configurationError("Qdrant provider returned an incompatible backend", null);
                    }
                } catch (VectorBackendException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw configurationError("Qdrant backend could not be created", exception);
                }
                resolvedQdrant = backend;
            }
            return backend;
        }
    }

    private <T> T qdrantCall(Supplier<T> call, VectorErrorCode fallbackError, String safeMessage) {
        try {
            return call.get();
        } catch (VectorBackendException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VectorBackendException(
                    VectorBackendType.QDRANT,
                    fallbackError,
                    safeMessage,
                    true,
                    exception
            );
        }
    }

    private void qdrantRun(Runnable call, VectorErrorCode fallbackError, String safeMessage) {
        qdrantCall(() -> {
            call.run();
            return null;
        }, fallbackError, safeMessage);
    }

    private static VectorBackendException configurationError(String safeMessage, Throwable cause) {
        return new VectorBackendException(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                safeMessage,
                false,
                cause
        );
    }

    private static VectorErrorCode errorCode(RuntimeException exception, VectorErrorCode fallback) {
        return exception instanceof VectorBackendException backendException
                ? backendException.getErrorCode()
                : fallback;
    }

    private static List<String> ids(List<VectorMatch> matches, int topK) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        return matches.stream().limit(topK).map(VectorMatch::id).distinct().toList();
    }

    private long elapsed(long started) {
        return Math.max(0L, nanoTime.getAsLong() - started);
    }

    @Override
    public void close() {
        if (ownsExecutors) {
            shadowExecutor.shutdownNow();
            timeoutScheduler.shutdownNow();
        }
    }

    private static ExecutorService newShadowExecutor() {
        return new ThreadPoolExecutor(
                2,
                4,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(SHADOW_QUEUE_CAPACITY),
                daemonThreadFactory("vector-shadow"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static ScheduledExecutorService newTimeoutScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                1,
                daemonThreadFactory("vector-shadow-timeout")
        );
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class ShadowSearchState {

        private final int topK;

        private boolean baselineDone;
        private List<String> baselineIds = List.of();
        private long baselineDuration;
        private VectorErrorCode baselineError;

        private boolean qdrantDone;
        private List<String> qdrantIds = List.of();
        private long qdrantDuration;
        private VectorErrorCode qdrantError;

        private boolean recorded;
        private ScheduledFuture<?> timeout;

        private ShadowSearchState(int topK) {
            this.topK = topK;
        }

        synchronized void baselineSucceeded(List<String> ids, long duration) {
            baselineDone = true;
            baselineIds = ids;
            baselineDuration = duration;
            completeIfReady();
        }

        synchronized void baselineFailed(VectorErrorCode errorCode, long duration) {
            baselineDone = true;
            baselineError = errorCode;
            baselineDuration = duration;
            completeIfReady();
        }

        synchronized void qdrantSucceeded(List<String> ids, long duration) {
            if (qdrantDone) {
                return;
            }
            qdrantDone = true;
            qdrantIds = ids;
            qdrantDuration = duration;
            cancelTimeout();
            completeIfReady();
        }

        synchronized boolean qdrantFailed(VectorErrorCode errorCode, long duration) {
            if (qdrantDone) {
                return false;
            }
            qdrantDone = true;
            qdrantError = errorCode;
            qdrantDuration = duration;
            cancelTimeout();
            completeIfReady();
            return true;
        }

        synchronized boolean qdrantTimedOut(long duration) {
            return qdrantFailed(VectorErrorCode.VECTOR_TIMEOUT, duration);
        }

        synchronized void timeoutFuture(ScheduledFuture<?> future) {
            if (qdrantDone) {
                future.cancel(false);
            } else {
                timeout = future;
            }
        }

        private void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }

        private void completeIfReady() {
            if (recorded || !baselineDone || !qdrantDone) {
                return;
            }
            recorded = true;
            VectorErrorCode error = qdrantError != null ? qdrantError : baselineError;
            metrics.record(ShadowObservation.search(
                    topK,
                    baselineIds,
                    qdrantIds,
                    baselineDuration,
                    qdrantDuration,
                    error
            ));
        }
    }
}
