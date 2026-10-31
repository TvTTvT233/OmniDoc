package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobError;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.store.ReviewM0PersistenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ReviewM0JobDispatcher implements SmartLifecycle, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ReviewM0JobDispatcher.class);
    private static final Duration DEFAULT_RECOVERY_INTERVAL = Duration.ofSeconds(1);
    private static final int DEFAULT_RECOVERY_BATCH_SIZE = 32;
    private static final AtomicInteger RECOVERY_THREAD_SEQUENCE = new AtomicInteger();

    private final ReviewM0JobStore jobStore;
    private final ReviewM0JobHandlerRegistry handlers;
    private final Clock clock;
    private final Duration recoveryInterval;
    private final int recoveryBatchSize;
    private final boolean recoveryEnabled;
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean recoveryFailureActive = new AtomicBoolean();
    private volatile ScheduledExecutorService recoveryExecutor;
    private volatile boolean running;

    public ReviewM0JobDispatcher(ReviewM0JobStore jobStore, ReviewM0JobHandlerRegistry handlers) {
        this(jobStore, handlers, Clock.systemUTC(), DEFAULT_RECOVERY_INTERVAL,
                DEFAULT_RECOVERY_BATCH_SIZE, true);
    }

    @Autowired
    public ReviewM0JobDispatcher(
            ReviewM0JobStore jobStore,
            ReviewM0JobHandlerRegistry handlers,
            ObjectProvider<ReviewM0PersistenceProperties> persistenceProperties
    ) {
        this(jobStore, handlers, Clock.systemUTC(), DEFAULT_RECOVERY_INTERVAL,
                DEFAULT_RECOVERY_BATCH_SIZE,
                persistenceEnabled(persistenceProperties));
    }

    ReviewM0JobDispatcher(
            ReviewM0JobStore jobStore,
            ReviewM0JobHandlerRegistry handlers,
            Clock clock,
            Duration recoveryInterval,
            int recoveryBatchSize,
            boolean recoveryEnabled
    ) {
        if (recoveryInterval == null || recoveryInterval.toMillis() < 1) {
            throw new IllegalArgumentException("recoveryInterval must be at least one millisecond");
        }
        if (recoveryBatchSize < 1 || recoveryBatchSize > 256) {
            throw new IllegalArgumentException("recoveryBatchSize must be between 1 and 256");
        }
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recoveryInterval = recoveryInterval;
        this.recoveryBatchSize = recoveryBatchSize;
        this.recoveryEnabled = recoveryEnabled;
    }

    public Optional<ReviewJob> runNext(
            String workerId,
            Instant now,
            Duration leaseDuration,
            int maximumAttempts
    ) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        Optional<ReviewJob> acquired = jobStore.acquireNext(workerId, now, leaseDuration);
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        ReviewJob leased = acquired.get();
        if (leased.attempt() > maximumAttempts) {
            return Optional.of(jobStore.fail(leased.jobId(), workerId,
                    new ReviewJobError(ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR, false,
                            "Review M0 execution attempt limit exceeded"), now));
        }
        ReviewM0JobHandler handler = handlers.find(leased.jobType()).orElse(null);
        if (handler == null) {
            return Optional.of(jobStore.fail(
                    leased.jobId(), workerId,
                    new ReviewJobError(
                            ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR,
                            false,
                            "No Review M0 handler is registered for " + leased.jobType()),
                    now));
        }
        try {
            handler.handle(leased, jobStore);
            ReviewJob after = jobStore.findOwned(leased.userId(), leased.caseId(), leased.jobId()).orElseThrow();
            if (after.status() == ReviewJobStatus.CANCEL_REQUESTED) {
                return Optional.of(jobStore.finalizeCancellation(after.jobId(), workerId, now));
            }
            if (!after.status().terminal()) {
                return Optional.of(jobStore.fail(
                        leased.jobId(), workerId,
                        new ReviewJobError(
                                ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR,
                                false,
                                "Review M0 handler returned without a terminal state"),
                        now));
            }
            return Optional.of(after);
        } catch (ReviewM0JobExecutionException error) {
            ReviewJob current = jobStore.findOwned(leased.userId(), leased.caseId(), leased.jobId()).orElseThrow();
            if (current.status() == ReviewJobStatus.CANCEL_REQUESTED) {
                return Optional.of(jobStore.finalizeCancellation(current.jobId(), workerId, now));
            }
            if (error.retryable() && current.attempt() < maximumAttempts) {
                return Optional.of(jobStore.releaseForRetry(
                        current.jobId(), workerId, current.version(), now));
            }
            return Optional.of(jobStore.fail(
                    current.jobId(), workerId,
                    new ReviewJobError(error.errorCode(), error.retryable(), safeMessage(error)),
                    now));
        } catch (RuntimeException error) {
            ReviewJob current = jobStore.findOwned(leased.userId(), leased.caseId(), leased.jobId()).orElseThrow();
            if (current.status().terminal()) {
                return Optional.of(current);
            }
            if (current.status() == ReviewJobStatus.CANCEL_REQUESTED) {
                return Optional.of(jobStore.finalizeCancellation(current.jobId(), workerId, now));
            }
            return Optional.of(jobStore.fail(
                    current.jobId(), workerId,
                    new ReviewJobError(
                            ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR,
                            false,
                            "Review M0 handler failed"),
                    now));
        }
    }

    private static String safeMessage(ReviewM0JobExecutionException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "Review M0 handler failed";
        }
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }

    private static boolean persistenceEnabled(
            ObjectProvider<ReviewM0PersistenceProperties> persistenceProperties
    ) {
        ReviewM0PersistenceProperties properties = persistenceProperties.getIfAvailable();
        return properties == null || properties.isEnabled();
    }

    @Override
    public void start() {
        if (!recoveryEnabled) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (running) {
                return;
            }
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(
                        task,
                        "review-m0-cancellation-recovery-" + RECOVERY_THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            recoveryExecutor = executor;
            running = true;
            try {
                executor.scheduleWithFixedDelay(
                        this::recoverCancellationsSafely,
                        0,
                        recoveryInterval.toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException error) {
                running = false;
                recoveryExecutor = null;
                executor.shutdownNow();
                throw error;
            }
        }
    }

    private void recoverCancellationsSafely() {
        if (!running) {
            return;
        }
        try {
            jobStore.recoverCancellations(clock.instant(), recoveryBatchSize);
            if (recoveryFailureActive.compareAndSet(true, false)) {
                log.info("Review M0 cancellation recovery resumed");
            }
        } catch (RuntimeException error) {
            if (recoveryFailureActive.compareAndSet(false, true)) {
                log.warn(
                        "Review M0 cancellation recovery scan failed; it will retry; errorType={}",
                        error.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor;
        synchronized (lifecycleMonitor) {
            if (!running) {
                return;
            }
            running = false;
            executor = recoveryExecutor;
            recoveryExecutor = null;
        }
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("Review M0 cancellation recovery did not stop within its bounded shutdown window");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return recoveryEnabled;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void close() {
        stop();
    }
}
