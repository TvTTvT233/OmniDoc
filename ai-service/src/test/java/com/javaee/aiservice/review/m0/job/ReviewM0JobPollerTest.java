package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.*;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0PersistenceProperties;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReviewM0JobPollerTest {
    @Test
    void springLifecycleAutomaticallyConsumesPersistedJobsAndStopsOnClose() throws Exception {
        var fixture = fixture();
        var store = store(fixture);
        store.create(queued("job-first"));
        var calls = new AtomicInteger();
        ReviewM0JobPoller poller;
        try (var context = context(store, calls, true)) {
            poller = context.getBean(ReviewM0JobPoller.class);
            poller.start(); // repeated lifecycle start must not create a second consumer
            awaitTerminal(store, "job-first");
            assertThat(store.findOwned("owner", "case-poll", "job-first").orElseThrow().status())
                    .isEqualTo(ReviewJobStatus.SUCCEEDED);
            assertThat(calls).hasValue(1);
            assertThat(poller.isRunning()).isTrue();
        }
        assertThat(poller.isRunning()).isFalse();
        store.create(queued("job-after-close"));
        Thread.sleep(1100);
        assertThat(store.findOwned("owner", "case-poll", "job-after-close").orElseThrow().status())
                .isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void disabledPersistenceDoesNotStartOrAcquireJobs() throws Exception {
        var store = store(fixture());
        store.create(queued("job-disabled"));
        var calls = new AtomicInteger();
        try (var context = context(store, calls, false)) {
            var poller = context.getBean(ReviewM0JobPoller.class);
            poller.start();
            assertThat(poller.isRunning()).isFalse();
            Thread.sleep(100);
            assertThat(store.findOwned("owner", "case-poll", "job-disabled").orElseThrow().status())
                    .isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void transientAcquisitionFailureDoesNotKillPolling() throws Exception {
        var store = spy(store(fixture()));
        store.create(queued("job-recover"));
        doThrow(new IllegalStateException("synthetic storage outage")).doCallRealMethod()
                .when(store).acquireNext(anyString(), any(), any());
        var calls = new AtomicInteger();
        try (var context = context(store, calls, true)) {
            awaitTerminal(store, "job-recover");
            assertThat(store.findOwned("owner", "case-poll", "job-recover").orElseThrow().status())
                    .isEqualTo(ReviewJobStatus.SUCCEEDED);
            assertThat(calls).hasValue(1);
            verify(store, atLeast(2)).acquireNext(anyString(), any(), any());
        }
    }

    @Test
    void repeatedlyExpiredLeaseStopsAtAttemptLimitWithoutInvokingHandlerAgain() throws Exception {
        var store = store(fixture());
        store.create(queued("job-exhausted"));
        Instant before = Instant.now().minusSeconds(20);
        for (int attempt = 0; attempt < 3; attempt++) {
            store.acquireNext("crashed-worker-" + attempt, before.plusSeconds(attempt * 2), Duration.ofSeconds(1))
                    .orElseThrow();
        }
        var calls = new AtomicInteger();
        try (var context = context(store, calls, true)) {
            awaitTerminal(store, "job-exhausted");
            var terminal = store.findOwned("owner", "case-poll", "job-exhausted").orElseThrow();
            assertThat(terminal.status()).isEqualTo(ReviewJobStatus.FAILED);
            assertThat(terminal.error().retryable()).isFalse();
            assertThat(calls).hasValue(0);
        }
    }

    private static AnnotationConfigApplicationContext context(
            JdbcReviewM0JobStore store, AtomicInteger calls, boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        var properties = new ReviewM0PersistenceProperties();
        properties.setEnabled(enabled);
        context.registerBean(ReviewM0PersistenceProperties.class, () -> properties);
        context.registerBean(ReviewM0JobStore.class, () -> store);
        ReviewM0JobHandler handler = new ReviewM0JobHandler() {
            public ReviewJobType jobType() { return ReviewJobType.FORMAL_REVIEW; }
            public void handle(ReviewJob job, ReviewM0JobStore jobs) {
                calls.incrementAndGet();
                jobs.completeWithResult(job.jobId(), job.leaseOwner(), Instant.now(),
                        () -> new ReviewJobResultRef("REPORT", "report-" + job.jobId()));
            }
        };
        context.registerBean(ReviewM0JobHandlerRegistry.class, () -> new ReviewM0JobHandlerRegistry(List.of(handler)));
        context.register(ReviewM0JobDispatcher.class, ReviewM0JobPoller.class);
        context.refresh();
        return context;
    }

    private static ReviewM0JdbcTestFixture fixture() {
        var fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-poll", "owner");
        return fixture;
    }

    private static JdbcReviewM0JobStore store(ReviewM0JdbcTestFixture fixture) {
        return new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
    }

    private static ReviewJob queued(String id) {
        return new ReviewJob(id, "case-poll", "owner", ReviewJobType.FORMAL_REVIEW, ReviewJobStatus.QUEUED,
                "{}", ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT, null, "idem-" + id,
                0, 1, 0, null, null, null, null, null, 0, ReviewM0StoreTestData.T0, null, null, null);
    }

    private static void awaitTerminal(JdbcReviewM0JobStore store, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (store.findOwned("owner", "case-poll", id).orElseThrow().status().terminal()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Persisted job did not reach a terminal state through lifecycle polling");
    }
}
