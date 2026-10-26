package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyRecord;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.JdbcReviewM0JobStore;
import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmission;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmissionService;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.IdempotencyState;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Synchronous formal-review gate. A rejected request creates neither a Job nor a report/finding. */
public final class FormalReviewSubmissionService {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final ReviewInputProvider inputProvider;
    private final ReviewReportReader reportReader;
    private final IncrementalReviewValidator incrementalValidator;
    private final ReviewM0JobSubmissionService jobSubmissionService;
    private final ReviewM0IdempotencyStore idempotencyStore;
    private final ReviewM0JobStore jobStore;
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
        this.idempotencyStore = new JdbcReviewM0IdempotencyStore(
                this.jdbcTemplate, Objects.requireNonNull(transactionManager));
        this.jobStore = new JdbcReviewM0JobStore(this.jdbcTemplate, transactionManager);
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
            requireFormalReviewScope(scope, caseId);
            lockOwnedCase(scope.ownerId(), caseId);
            String clientRequestDigest = canonicalClientRequestDigest(scope, caseId, request);
            Optional<ReviewM0JobSubmission> replay = replayCompleted(
                    scope, caseId, clientRequestDigest);
            if (replay.isPresent()) {
                return replay.get();
            }
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
            String envelopeDigest = envelope.digest();
            Instant now = clock.instant();
            ReviewJob queued = new ReviewJob(
                    ReviewM0Ids.next("job-"),
                    caseId,
                    scope.ownerId(),
                    request.mode() == ReviewMode.FULL
                            ? ReviewJobType.FORMAL_REVIEW : ReviewJobType.INCREMENTAL_REVIEW,
                    ReviewJobStatus.QUEUED,
                    requestJson,
                    envelopeDigest,
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
            return jobSubmissionService.submit(scope, clientRequestDigest, queued);
        }));
    }

    private static void requireFormalReviewScope(IdempotencyScope scope, String caseId) {
        String expectedPath = "/api/review-m0/cases/" + caseId + "/reviews";
        if (!"POST".equals(scope.httpMethod()) || !expectedPath.equals(scope.canonicalPath())) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                    "Formal review endpoint scope was not found");
        }
    }

    private Optional<ReviewM0JobSubmission> replayCompleted(
            IdempotencyScope scope,
            String caseId,
            String clientRequestDigest
    ) {
        Optional<IdempotencyRecord> existing = idempotencyStore.find(scope);
        if (existing.isEmpty()) return Optional.empty();
        IdempotencyRecord record = existing.get();
        if (!record.requestDigest().equals(clientRequestDigest)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT,
                    "Idempotency key was reused with a different canonical payload");
        }
        if (record.state() != IdempotencyState.COMPLETED) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_JOB_QUEUE_UNAVAILABLE,
                    "The same Job submission is still in progress");
        }
        if (!"JOB".equals(record.responseType()) || record.responseStatus() == null) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT,
                    "Idempotency key is bound to a different resource type");
        }
        ReviewJob original = jobStore.findOwned(scope.ownerId(), caseId, record.responseId())
                .orElseThrow(() -> new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_JOB_QUEUE_UNAVAILABLE,
                        "Completed idempotency record refers to a missing Job"));
        return Optional.of(new ReviewM0JobSubmission(original, record.responseStatus(), true));
    }

    private static String canonicalClientRequestDigest(
            IdempotencyScope scope,
            String caseId,
            FormalReviewRequest request
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseReportId", request.baseReportId());
        body.put("changedMaterialIds", request.changedMaterialIds());
        body.put("checklistSnapshotId", request.checklistSnapshotId());
        body.put("mode", request.mode());
        body.put("uploadConfirmationId", request.uploadConfirmationId());
        Map<String, Object> canonicalRequest = new LinkedHashMap<>();
        canonicalRequest.put("caseId", caseId);
        canonicalRequest.put("canonicalPath", scope.canonicalPath());
        canonicalRequest.put("httpMethod", scope.httpMethod());
        canonicalRequest.put("ownerId", scope.ownerId());
        canonicalRequest.put("payload", body);
        return CanonicalJson.sha256(canonicalRequest);
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
