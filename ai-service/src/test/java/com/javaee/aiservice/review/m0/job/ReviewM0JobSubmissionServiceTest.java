package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewM0JobSubmissionServiceTest {
    @Test
    void queuedJobAndCompletedIdempotencyRecordAreOneTransactionAndReplayOriginalJob() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-submit", "owner");
        ReviewM0JobSubmissionService service = service(fixture);
        IdempotencyScope scope = new IdempotencyScope(
                "owner", "POST", "/api/review-m0/cases/case-submit/reviews", "request");
        ReviewJob job = queued("job-submit", "case-submit");

        ReviewM0JobSubmission first = service.submit(scope, "a".repeat(64), job);
        ReviewM0JobSubmission replay = service.submit(scope, "a".repeat(64), job);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.responseStatus()).isEqualTo(202);
        assertThat(replay.job()).isEqualTo(first.job());
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM review_m0_job", Integer.class)).isEqualTo(1);
    }

    @Test
    void differentPayloadConflictsAndFailedJobInsertRollsBackIdempotencyClaim() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-submit", "owner");
        ReviewM0JobSubmissionService service = service(fixture);
        IdempotencyScope scope = new IdempotencyScope("owner", "POST", "/fixed", "key");
        service.submit(scope, "a".repeat(64), queued("job-one", "case-submit"));

        assertThatThrownBy(() -> service.submit(scope, "b".repeat(64), queued("job-two", "case-submit")))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT);

        IdempotencyScope rollbackScope = new IdempotencyScope("owner", "POST", "/missing", "rollback");
        assertThatThrownBy(() -> service.submit(
                rollbackScope, "c".repeat(64), queued("job-missing", "case-missing")))
                .isInstanceOf(RuntimeException.class);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency WHERE canonical_path = '/missing'",
                Integer.class)).isZero();
    }

    private static ReviewM0JobSubmissionService service(ReviewM0JdbcTestFixture fixture) {
        return new ReviewM0JobSubmissionService(
                new JdbcReviewM0IdempotencyStore(fixture.jdbc(), fixture.transactions()),
                new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions()),
                fixture.transactions());
    }

    private static ReviewJob queued(String jobId, String caseId) {
        return new ReviewJob(
                jobId, caseId, "owner", ReviewJobType.FORMAL_REVIEW, ReviewJobStatus.QUEUED,
                "{}", ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT, null, "idem-" + jobId,
                0, 1, 0, null, null, null, null, null,
                0, ReviewM0StoreTestData.T0, null, null, null);
    }
}
