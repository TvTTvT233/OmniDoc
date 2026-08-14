package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaim;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaimStatus;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Component
public class ReviewM0JobSubmissionService {
    private static final int ACCEPTED = 202;

    private final ReviewM0IdempotencyStore idempotencyStore;
    private final ReviewM0JobStore jobStore;
    private final TransactionTemplate transactions;

    public ReviewM0JobSubmissionService(
            ReviewM0IdempotencyStore idempotencyStore,
            ReviewM0JobStore jobStore,
            PlatformTransactionManager transactionManager
    ) {
        this.idempotencyStore = idempotencyStore;
        this.jobStore = jobStore;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ReviewM0JobSubmission submit(
            IdempotencyScope scope,
            String requestDigest,
            ReviewJob queuedJob
    ) {
        if (!scope.ownerId().equals(queuedJob.userId())) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                    "Job owner does not match authenticated idempotency scope");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            IdempotencyClaim claim = idempotencyStore.claim(scope, requestDigest);
            if (claim.status() == IdempotencyClaimStatus.REPLAY_COMPLETED) {
                if (!"JOB".equals(claim.record().responseType())) {
                    throw new ReviewM0Exception(
                            ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT,
                            "Idempotency key is bound to a different resource type");
                }
                ReviewJob original = jobStore.findOwned(
                                scope.ownerId(), queuedJob.caseId(), claim.record().responseId())
                        .orElseThrow(() -> new ReviewM0Exception(
                                ReviewM0ErrorCode.REVIEW_M0_JOB_QUEUE_UNAVAILABLE,
                                "Completed idempotency record refers to a missing Job"));
                return new ReviewM0JobSubmission(original, claim.record().responseStatus(), true);
            }
            if (claim.status() == IdempotencyClaimStatus.REPLAY_IN_PROGRESS) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_JOB_QUEUE_UNAVAILABLE,
                        "The same Job submission is still in progress");
            }
            ReviewJob created = jobStore.create(queuedJob);
            idempotencyStore.complete(scope, requestDigest, ACCEPTED, "JOB", created.jobId());
            return new ReviewM0JobSubmission(created, ACCEPTED, false);
        }));
    }
}
