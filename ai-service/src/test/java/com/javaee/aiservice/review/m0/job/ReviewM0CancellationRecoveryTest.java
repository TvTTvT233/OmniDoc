package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobError;
import com.javaee.aiservice.review.m0.model.ReviewJobResultRef;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0CancellationRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-08-23T01:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration SCAN_INTERVAL = Duration.ofMillis(5);

    @Test
    void persistedRunningCancellationConvergesAfterRestartWithoutCallingHandlerOrPublishing() throws Exception {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-restart");
        JdbcReviewM0JobStore originalStore = store(fixture);
        originalStore.create(queued("job-restart", "case-restart"));
        originalStore.acquireNext("worker-before-crash", NOW, LEASE).orElseThrow();
        originalStore.requestCancelOwned("owner", "case-restart", "job-restart", NOW.plusSeconds(1));
        AtomicInteger handlerCalls = new AtomicInteger();

        try (ReviewM0JobDispatcher original = dispatcher(
                originalStore, handlerCalls, NOW.plusSeconds(2))) {
            original.start();
            Thread.sleep(30);
            assertThat(job(originalStore, "case-restart", "job-restart").status())
                    .isEqualTo(ReviewJobStatus.CANCEL_REQUESTED);
        }

        JdbcReviewM0JobStore restartedStore = store(fixture);
        try (ReviewM0JobDispatcher restarted = dispatcher(
                restartedStore, handlerCalls, NOW.plusSeconds(31))) {
            restarted.start();
            restarted.start();
            ReviewJob cancelled = awaitStatus(
                    restartedStore, "case-restart", "job-restart", ReviewJobStatus.CANCELLED);

            assertThat(cancelled.result()).isNull();
            assertThat(cancelled.error()).isNull();
            assertThat(restartedStore.listCheckpoints("job-restart")).isEmpty();
            assertThat(handlerCalls).hasValue(0);
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM review_m0_report WHERE job_id = 'job-restart'", Integer.class))
                    .isZero();
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM review_m0_remote_call WHERE job_id = 'job-restart'", Integer.class))
                    .isZero();
        }

        long terminalVersion = job(restartedStore, "case-restart", "job-restart").version();
        try (ReviewM0JobDispatcher repeatedRestart = dispatcher(
                store(fixture), handlerCalls, NOW.plusSeconds(60))) {
            repeatedRestart.start();
            Thread.sleep(30);
        }
        assertThat(job(restartedStore, "case-restart", "job-restart").version())
                .isEqualTo(terminalVersion);
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void twoDispatchersCompetingForQueuedCancellationProduceOneCasTransition() throws Exception {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-race");
        JdbcReviewM0JobStore setupStore = store(fixture);
        setupStore.create(queued("job-race", "case-race"));
        setupStore.requestCancelOwned("owner", "case-race", "job-race", NOW);
        CountDownLatch scansReady = new CountDownLatch(2);
        CountDownLatch releaseScans = new CountDownLatch(1);
        AtomicInteger recoveredTransitions = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();

        class CoordinatedStore extends JdbcReviewM0JobStore {
            CoordinatedStore() {
                super(fixture.jdbc(), fixture.transactions());
            }

            @Override
            public List<ReviewJob> recoverCancellations(Instant now, int maximumJobs) {
                scansReady.countDown();
                try {
                    if (!releaseScans.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Cancellation recovery test barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Cancellation recovery test barrier interrupted");
                }
                List<ReviewJob> recovered = super.recoverCancellations(now, maximumJobs);
                recoveredTransitions.addAndGet(recovered.size());
                return recovered;
            }
        }

        try (ReviewM0JobDispatcher first = dispatcher(
                new CoordinatedStore(), handlerCalls, NOW.plusSeconds(1));
             ReviewM0JobDispatcher second = dispatcher(
                     new CoordinatedStore(), handlerCalls, NOW.plusSeconds(1))) {
            first.start();
            second.start();
            assertThat(scansReady.await(2, TimeUnit.SECONDS)).isTrue();
            releaseScans.countDown();

            ReviewJob cancelled = awaitStatus(
                    setupStore, "case-race", "job-race", ReviewJobStatus.CANCELLED);
            assertThat(cancelled.version()).isEqualTo(2);
            assertThat(recoveredTransitions).hasValue(1);
            assertThat(handlerCalls).hasValue(0);
        }
    }

    @Test
    void recoveryScanIsBoundedAndLeavesAllTerminalStatesUnchanged() {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-terminal");
        JdbcReviewM0JobStore store = store(fixture);
        for (int index = 1; index <= 3; index++) {
            String jobId = "job-cancel-" + index;
            store.create(queued(jobId, "case-terminal"));
            store.requestCancelOwned("owner", "case-terminal", jobId, NOW.plusSeconds(index));
        }

        assertThat(store.recoverCancellations(NOW.plusSeconds(10), 2)).hasSize(2);
        assertThat(store.recoverCancellations(NOW.plusSeconds(10), 2)).hasSize(1);

        store.create(queued("job-succeeded", "case-terminal"));
        store.acquireNext("worker-success", NOW.plusSeconds(20), LEASE).orElseThrow();
        store.completeWithResult(
                "job-succeeded", "worker-success", NOW.plusSeconds(21),
                () -> new ReviewJobResultRef("TEST_RESULT", "result-1"));
        store.create(queued("job-failed", "case-terminal"));
        store.acquireNext("worker-failure", NOW.plusSeconds(20), LEASE).orElseThrow();
        store.fail(
                "job-failed", "worker-failure",
                new ReviewJobError(
                        ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                        true,
                        "Synthetic storage failure"),
                NOW.plusSeconds(21));

        List<ReviewJob> before = List.of(
                job(store, "case-terminal", "job-cancel-1"),
                job(store, "case-terminal", "job-cancel-2"),
                job(store, "case-terminal", "job-cancel-3"),
                job(store, "case-terminal", "job-succeeded"),
                job(store, "case-terminal", "job-failed"));
        assertThat(store.recoverCancellations(NOW.plusSeconds(60), 32)).isEmpty();
        List<ReviewJob> after = before.stream()
                .map(item -> job(store, "case-terminal", item.jobId()))
                .toList();

        assertThat(after).isEqualTo(before);
        assertThat(after).extracting(ReviewJob::status).containsExactly(
                ReviewJobStatus.CANCELLED,
                ReviewJobStatus.CANCELLED,
                ReviewJobStatus.CANCELLED,
                ReviewJobStatus.SUCCEEDED,
                ReviewJobStatus.FAILED);
    }

    @Test
    void recoveryRetriesAfterStorageErrorAndDoesNotExposeJobToHandler() throws Exception {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-storage-retry");
        JdbcReviewM0JobStore delegate = store(fixture);
        delegate.create(queued("job-storage-retry", "case-storage-retry"));
        delegate.requestCancelOwned("owner", "case-storage-retry", "job-storage-retry", NOW);
        AtomicInteger scans = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        JdbcReviewM0JobStore flakyStore = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions()) {
            @Override
            public List<ReviewJob> recoverCancellations(Instant now, int maximumJobs) {
                if (scans.incrementAndGet() == 1) {
                    throw new IllegalStateException("synthetic-sensitive-storage-detail");
                }
                return super.recoverCancellations(now, maximumJobs);
            }
        };

        try (ReviewM0JobDispatcher dispatcher = dispatcher(
                flakyStore, handlerCalls, NOW.plusSeconds(1))) {
            dispatcher.start();
            awaitStatus(delegate, "case-storage-retry", "job-storage-retry", ReviewJobStatus.CANCELLED);
        }

        assertThat(scans.get()).isGreaterThanOrEqualTo(2);
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void stoppedDispatcherPerformsNoFurtherScansOrCancellationWrites() throws Exception {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-stop");
        JdbcReviewM0JobStore delegate = store(fixture);
        AtomicInteger scans = new AtomicInteger();
        JdbcReviewM0JobStore countingStore = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions()) {
            @Override
            public List<ReviewJob> recoverCancellations(Instant now, int maximumJobs) {
                scans.incrementAndGet();
                return super.recoverCancellations(now, maximumJobs);
            }
        };
        ReviewM0JobDispatcher dispatcher = dispatcher(
                countingStore, new AtomicInteger(), NOW.plusSeconds(1));
        dispatcher.start();
        awaitScan(scans);
        dispatcher.stop();
        int scansAtStop = scans.get();

        delegate.create(queued("job-after-stop", "case-stop"));
        delegate.requestCancelOwned("owner", "case-stop", "job-after-stop", NOW);
        Thread.sleep(30);

        assertThat(dispatcher.isRunning()).isFalse();
        assertThat(scans).hasValue(scansAtStop);
        assertThat(job(delegate, "case-stop", "job-after-stop").status())
                .isEqualTo(ReviewJobStatus.CANCEL_REQUESTED);
        dispatcher.close();
    }

    private static ReviewM0JobDispatcher dispatcher(
            ReviewM0JobStore store, AtomicInteger handlerCalls, Instant now
    ) {
        ReviewM0JobHandler handler = new ReviewM0JobHandler() {
            @Override
            public ReviewJobType jobType() {
                return ReviewJobType.FORMAL_REVIEW;
            }

            @Override
            public void handle(ReviewJob leasedJob, ReviewM0JobStore jobStore) {
                handlerCalls.incrementAndGet();
            }
        };
        return new ReviewM0JobDispatcher(
                store,
                new ReviewM0JobHandlerRegistry(List.of(handler)),
                Clock.fixed(now, ZoneOffset.UTC),
                SCAN_INTERVAL,
                1,
                true);
    }

    private static ReviewM0JdbcTestFixture fixtureWithCase(String caseId) {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, caseId, "owner");
        return fixture;
    }

    private static JdbcReviewM0JobStore store(ReviewM0JdbcTestFixture fixture) {
        return new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
    }

    private static ReviewJob queued(String jobId, String caseId) {
        return new ReviewJob(
                jobId, caseId, "owner", ReviewJobType.FORMAL_REVIEW, ReviewJobStatus.QUEUED,
                "{}", ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT, null, "idem-" + jobId,
                0, 3, 0, null, null, null, null, null,
                0, NOW.minusSeconds(1), null, null, null);
    }

    private static ReviewJob job(JdbcReviewM0JobStore store, String caseId, String jobId) {
        return store.findOwned("owner", caseId, jobId).orElseThrow();
    }

    private static ReviewJob awaitStatus(
            JdbcReviewM0JobStore store,
            String caseId,
            String jobId,
            ReviewJobStatus expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        ReviewJob current;
        do {
            current = job(store, caseId, jobId);
            if (current.status() == expected) {
                return current;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Job did not reach expected terminal status; actual=" + current.status());
    }

    private static void awaitScan(AtomicInteger scans) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (scans.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(scans.get()).isGreaterThan(0);
    }
}
