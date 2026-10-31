package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.store.ReviewM0PersistenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded single-consumer polling; ownership and publication remain in the JDBC dispatcher. */
@Component
public final class ReviewM0JobPoller implements SmartLifecycle, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ReviewM0JobPoller.class);
    private final ReviewM0JobDispatcher dispatcher;
    private final ReviewM0PersistenceProperties properties;
    private final String workerId = "review-m0-" + UUID.randomUUID();
    private final AtomicBoolean failureActive = new AtomicBoolean();
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public ReviewM0JobPoller(ReviewM0JobDispatcher dispatcher, ReviewM0PersistenceProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        // A handler that ignores interruption must finish before this instance can restart.
        if (executor != null && !executor.isTerminated()) {
            throw new IllegalStateException("Review worker is still stopping");
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, workerId);
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        executor.scheduleWithFixedDelay(this::pollSafely, 0, 1, TimeUnit.SECONDS);
    }

    private void pollSafely() {
        if (!running) return;
        try {
            dispatcher.runNext(workerId, Instant.now(), Duration.ofMinutes(2), 3);
            if (failureActive.compareAndSet(true, false)) log.info("Review M0 worker resumed");
        } catch (RuntimeException error) {
            // Never log request contents or exception messages; next tick retries the durable queue.
            if (failureActive.compareAndSet(false, true)) {
                log.warn("Review M0 worker polling failed; it will retry; errorType={}",
                        error.getClass().getSimpleName());
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("Review M0 worker is still stopping; its persisted lease protects publication");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return properties.isEnabled(); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 99; }
    @Override public void close() { stop(); }
}
