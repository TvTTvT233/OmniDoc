package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmission;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmissionService;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewMode;
import com.javaee.aiservice.review.m0.report.PublishedReview;
import com.javaee.aiservice.review.m0.report.ReviewM0Ids;
import com.javaee.aiservice.review.m0.report.ReviewReportReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Synchronous formal-review gate. A rejected request creates neither a Job nor a report/finding. */
public final class FormalReviewSubmissionService {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final ReviewInputProvider inputProvider;
    private final ReviewReportReader reportReader;
    private final IncrementalReviewValidator incrementalValidator;
    private final ReviewM0JobSubmissionService jobSubmissionService;
    private final Clock clock;

    public FormalReviewSubmissionService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ReviewInputProvider inputProvider,
            ReviewReportReader reportReader,
            IncrementalReviewValidator incrementalValidator,
            ReviewM0JobSubmissionService jobSubmissionService,
            Clock clock
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.inputProvider = Objects.requireNonNull(inputProvider);
        this.reportReader = Objects.requireNonNull(reportReader);
        this.incrementalValidator = Objects.requireNonNull(incrementalValidator);
        this.jobSubmissionService = Objects.requireNonNull(jobSubmissionService);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReviewM0JobSubmission submitOwned(
            IdempotencyScope scope,
            String caseId,
            FormalReviewRequest request
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(request, "request");
        return Objects.requireNonNull(transactions.execute(status -> {
            lockOwnedCase(scope.ownerId(), caseId);
            CapturedReviewInput input = inputProvider.captureForSubmission(
                    scope.ownerId(), caseId, request.checklistSnapshotId(), request.uploadConfirmationId());
            if (request.mode() == ReviewMode.INCREMENTAL) {
                PublishedReview base = reportReader.requireOwned(
                        scope.ownerId(), caseId, request.baseReportId());
                incrementalValidator.validate(
                        base.inputManifest(), input.manifest(), request.changedMaterialIds());
            }
            ReviewJobRequestEnvelope envelope = new ReviewJobRequestEnvelope(request, input.manifest());
            String requestJson = envelope.canonicalJson();
            String requestDigest = envelope.digest();
            Instant now = clock.instant();
            ReviewJob queued = new ReviewJob(
                    ReviewM0Ids.next("job-"),
                    caseId,
                    scope.ownerId(),
                    request.mode() == ReviewMode.FULL
                            ? ReviewJobType.FORMAL_REVIEW : ReviewJobType.INCREMENTAL_REVIEW,
                    ReviewJobStatus.QUEUED,
                    requestJson,
                    requestDigest,
                    input.manifest().fingerprint().sha256(),
                    scope.idempotencyKey(),
                    0,
                    Math.max(1, input.checklist().items().size() + input.manifest().materials().size()),
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    now,
                    null,
                    null,
                    null);
            return jobSubmissionService.submit(scope, requestDigest, queued);
        }));
    }

    private void lockOwnedCase(String ownerId, String caseId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT case_id FROM review_m0_case WHERE case_id = ? AND user_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), caseId, ownerId);
        if (rows.isEmpty()) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND,
                    "Review case was not found");
        }
    }
}
