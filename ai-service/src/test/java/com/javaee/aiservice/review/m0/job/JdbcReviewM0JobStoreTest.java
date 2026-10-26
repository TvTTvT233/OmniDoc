package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.RequirementOrigin;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobError;
import com.javaee.aiservice.review.m0.model.ReviewJobResultRef;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.store.JdbcReviewCaseStore;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewM0JobStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    void twoWorkersCompetingForOneJobProduceOneLeaseHolder() throws Exception {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-race");
        JdbcReviewM0JobStore store = store(fixture);
        store.create(queued("job-race", "case-race", "owner", ReviewJobType.FORMAL_REVIEW));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return store.acquireNext("worker-a", NOW, LEASE);
            });
            var second = executor.submit(() -> {
                start.await();
                return store.acquireNext("worker-b", NOW, LEASE);
            });
            start.countDown();
            List<java.util.Optional<ReviewJob>> results = List.of(first.get(), second.get());
            assertThat(results).filteredOn(java.util.Optional::isPresent).hasSize(1);
            ReviewJob leased = results.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
            assertThat(leased.leaseOwner()).isIn("worker-a", "worker-b");
            assertThat(leased.attempt()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseIsRecoverableAndOldWorkerCannotAdvance() {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-recover");
        JdbcReviewM0JobStore store = store(fixture);
        store.create(queued("job-recover", "case-recover", "owner", ReviewJobType.FORMAL_REVIEW));
        ReviewJob first = store.acquireNext("worker-old", NOW, LEASE).orElseThrow();

        ReviewJob recovered = store.acquireNext("worker-new", NOW.plusSeconds(31), LEASE).orElseThrow();

        assertThat(recovered.leaseOwner()).isEqualTo("worker-new");
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThatThrownBy(() -> store.heartbeat(
                first.jobId(), "worker-old", first.version(), NOW.plusSeconds(32), LEASE))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT);
    }

    @Test
    void checkpointReplayIsIdempotentAndDifferentPayloadIsRejected() {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-checkpoint");
        JdbcReviewM0JobStore store = store(fixture);
        store.create(queued("job-checkpoint", "case-checkpoint", "owner", ReviewJobType.FORMAL_REVIEW));
        store.acquireNext("worker", NOW, LEASE).orElseThrow();

        String doneDigest = CanonicalJson.sha256(Map.of("done", true));
        CheckpointWrite first = store.saveCheckpoint(
                "job-checkpoint", "worker", "material:1", "{\"done\":true}", doneDigest,
                1, 3, NOW.plusSeconds(1), LEASE);
        CheckpointWrite replay = store.saveCheckpoint(
                "job-checkpoint", "worker", "material:1", "{\"done\":true}", doneDigest,
                1, 3, NOW.plusSeconds(2), LEASE);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(store.listCheckpoints("job-checkpoint")).hasSize(1);
        assertThat(replay.job().progressCompleted()).isEqualTo(1);
        assertThatThrownBy(() -> store.saveCheckpoint(
                "job-checkpoint", "worker", "material:1", "{\"done\":false}",
                CanonicalJson.sha256(Map.of("done", false)),
                2, 3, NOW.plusSeconds(3), LEASE))
                .isInstanceOf(ReviewM0Exception.class);
    }

    @Test
    void queuedAndRunningCancellationStopNewWorkAndPublishNoResult() {
        ReviewM0JdbcTestFixture queuedFixture = fixtureWithCase("case-cancel-q");
        JdbcReviewM0JobStore queuedStore = store(queuedFixture);
        queuedStore.create(queued("job-cancel-q", "case-cancel-q", "owner", ReviewJobType.FORMAL_REVIEW));
        ReviewJob requested = queuedStore.requestCancelOwned("owner", "case-cancel-q", "job-cancel-q", NOW);
        assertThat(requested.status()).isEqualTo(ReviewJobStatus.CANCEL_REQUESTED);
        assertThat(queuedStore.acquireNext("worker", NOW, LEASE)).isEmpty();
        ReviewJob cancelled = queuedStore.finalizeCancellation("job-cancel-q", null, NOW.plusSeconds(1));
        assertThat(cancelled.status()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(cancelled.result()).isNull();
        assertThat(queuedStore.requestCancelOwned("owner", "case-cancel-q", "job-cancel-q", NOW.plusSeconds(2)))
                .isEqualTo(cancelled);

        ReviewM0JdbcTestFixture runningFixture = fixtureWithCase("case-cancel-r");
        JdbcReviewM0JobStore runningStore = store(runningFixture);
        runningStore.create(queued("job-cancel-r", "case-cancel-r", "owner", ReviewJobType.FORMAL_REVIEW));
        runningStore.acquireNext("worker", NOW, LEASE).orElseThrow();
        runningStore.requestCancelOwned("owner", "case-cancel-r", "job-cancel-r", NOW.plusSeconds(1));
        assertThatThrownBy(() -> runningStore.saveCheckpoint(
                "job-cancel-r", "worker", "new-unit", "{}", ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT,
                1, 1, NOW.plusSeconds(2), LEASE)).isInstanceOf(ReviewM0Exception.class);
        assertThat(runningStore.acquireNext("other", NOW.plusSeconds(2), LEASE)).isEmpty();
        assertThat(runningStore.finalizeCancellation("job-cancel-r", "worker", NOW.plusSeconds(3)).status())
                .isEqualTo(ReviewJobStatus.CANCELLED);
    }

    @Test
    void publicationRollbackAndReplayNeverDuplicateSideEffects() {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-publish");
        JdbcReviewM0JobStore store = store(fixture);
        store.create(queued("job-publish", "case-publish", "owner", ReviewJobType.FORMAL_REVIEW));
        store.acquireNext("worker", NOW, LEASE).orElseThrow();
        AtomicInteger callbacks = new AtomicInteger();

        assertThatThrownBy(() -> store.completeWithResult(
                "job-publish", "worker", NOW.plusSeconds(1), () -> {
                    callbacks.incrementAndGet();
                    fixture.jdbc().update("""
                                    INSERT INTO review_m0_requirement(requirement_id, case_id, origin, created_at)
                                    VALUES ('result-resource', 'case-publish', 'USER_ADDED', ?)
                                    """,
                            Timestamp.from(NOW));
                    throw new IllegalStateException("synthetic crash before job success");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_requirement WHERE requirement_id = 'result-resource'",
                Integer.class)).isZero();
        assertThat(store.findOwned("owner", "case-publish", "job-publish").orElseThrow().status())
                .isEqualTo(ReviewJobStatus.RUNNING);

        ReviewJob completed = store.completeWithResult(
                "job-publish", "worker", NOW.plusSeconds(2), () -> {
                    callbacks.incrementAndGet();
                    fixture.jdbc().update("""
                                    INSERT INTO review_m0_requirement(requirement_id, case_id, origin, created_at)
                                    VALUES ('result-resource', 'case-publish', ?, ?)
                                    """,
                            RequirementOrigin.USER_ADDED.name(), Timestamp.from(NOW));
                    return new ReviewJobResultRef("TEST_RESOURCE", "result-resource");
                });
        ReviewJob replay = store.completeWithResult(
                "job-publish", "worker", NOW.plusSeconds(3), () -> {
                    callbacks.incrementAndGet();
                    throw new AssertionError("post-publication replay executed side effect");
                });

        assertThat(completed.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(replay).isEqualTo(completed);
        assertThat(callbacks).hasValue(2);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_requirement WHERE requirement_id = 'result-resource'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void failuresAreTerminalSystemErrorsAndContainNoResult() {
        ReviewM0JdbcTestFixture fixture = fixtureWithCase("case-fail");
        JdbcReviewM0JobStore store = store(fixture);
        store.create(queued("job-fail", "case-fail", "owner", ReviewJobType.CHECKLIST_EXTRACTION));
        store.acquireNext("worker", NOW, LEASE).orElseThrow();
        ReviewJobError error = new ReviewJobError(
                ReviewM0ErrorCode.REVIEW_M0_REMOTE_MODEL_FAILED, true, "Remote extraction failed");

        ReviewJob failed = store.fail("job-fail", "worker", error, NOW.plusSeconds(1));

        assertThat(failed.status()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.error()).isEqualTo(error);
        assertThat(failed.result()).isNull();
        assertThat(store.requestCancelOwned("owner", "case-fail", "job-fail", NOW.plusSeconds(2)))
                .isEqualTo(failed);
    }

    private static ReviewM0JdbcTestFixture fixtureWithCase(String caseId) {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, caseId, "owner");
        return fixture;
    }

    private static JdbcReviewM0JobStore store(ReviewM0JdbcTestFixture fixture) {
        return new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
    }

    private static ReviewJob queued(String jobId, String caseId, String owner, ReviewJobType type) {
        return new ReviewJob(
                jobId, caseId, owner, type, ReviewJobStatus.QUEUED,
                "{}", ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT, null, "idem-" + jobId,
                0, 3, 0, null, null, null, null, null,
                0, NOW.minusSeconds(1), null, null, null);
    }
}
