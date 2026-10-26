package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobResultRef;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0JobDispatcherTest {
    @Test
    void retryableHandlerUsesExpiredLeaseRecoveryAndPublishesOnceWithinBound() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-dispatch", "owner");
        JdbcReviewM0JobStore store = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        store.create(queued("job-dispatch", "case-dispatch", ReviewJobType.FORMAL_REVIEW));
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        ReviewM0JobHandler handler = new ReviewM0JobHandler() {
            @Override
            public ReviewJobType jobType() {
                return ReviewJobType.FORMAL_REVIEW;
            }

            @Override
            public void handle(ReviewJob leasedJob, ReviewM0JobStore jobStore) {
                if (calls.incrementAndGet() < 3) {
                    throw new ReviewM0JobExecutionException(
                            ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                            true,
                            "Synthetic retryable failure",
                            null);
                }
                jobStore.completeWithResult(
                        leasedJob.jobId(), leasedJob.leaseOwner(), NOW,
                        () -> {
                            publications.incrementAndGet();
                            return new ReviewJobResultRef("REPORT", "report-dispatch");
                        });
            }
        };
        ReviewM0JobDispatcher dispatcher = new ReviewM0JobDispatcher(
                store, new ReviewM0JobHandlerRegistry(List.of(handler)));

        assertThat(dispatcher.runNext("worker", NOW, Duration.ofSeconds(30), 3).orElseThrow().status())
                .isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(dispatcher.runNext("worker", NOW, Duration.ofSeconds(30), 3).orElseThrow().status())
                .isEqualTo(ReviewJobStatus.RUNNING);
        ReviewJob succeeded = dispatcher.runNext("worker", NOW, Duration.ofSeconds(30), 3).orElseThrow();

        assertThat(succeeded.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(succeeded.attempt()).isEqualTo(3);
        assertThat(calls).hasValue(3);
        assertThat(publications).hasValue(1);
    }

    @Test
    void missingHandlerFailsDeterministicallyWithoutPublishingResult() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-no-handler", "owner");
        JdbcReviewM0JobStore store = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        store.create(queued("job-no-handler", "case-no-handler", ReviewJobType.INCREMENTAL_REVIEW));
        ReviewM0JobDispatcher dispatcher = new ReviewM0JobDispatcher(
                store, new ReviewM0JobHandlerRegistry(List.of()));

        ReviewJob failed = dispatcher.runNext("worker", NOW, Duration.ofSeconds(30), 3).orElseThrow();

        assertThat(failed.status()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.error().errorCode()).isEqualTo(ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR);
        assertThat(failed.result()).isNull();
    }

    private static final Instant NOW = ReviewM0StoreTestData.T0.plusSeconds(10);

    private static ReviewJob queued(String jobId, String caseId, ReviewJobType type) {
        return new ReviewJob(
                jobId, caseId, "owner", type, ReviewJobStatus.QUEUED,
                "{}", ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT, null, "idem-" + jobId,
                0, 1, 0, null, null, null, null, null,
                0, ReviewM0StoreTestData.T0, null, null, null);
    }
}
