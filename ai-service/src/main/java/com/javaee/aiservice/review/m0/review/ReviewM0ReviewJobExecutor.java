package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.job.ReviewM0JobExecutionException;
import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewMode;
import com.javaee.aiservice.review.m0.report.PublishedReview;
import com.javaee.aiservice.review.m0.report.ReviewReportAssembler;
import com.javaee.aiservice.review.m0.report.ReviewReportReader;
import com.javaee.aiservice.review.m0.store.ReviewReportStore;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared implementation behind the two M0-02 SPI handlers. */
public final class ReviewM0ReviewJobExecutor {
    private static final Duration CHECKPOINT_LEASE_EXTENSION = Duration.ofMinutes(2);

    private final ReviewInputProvider inputProvider;
    private final ReviewReportReader reportReader;
    private final IncrementalReviewValidator incrementalValidator;
    private final DeterministicReviewEngine engine;
    private final ReviewReportAssembler assembler;
    private final ReviewReportStore reportStore;
    private final Clock clock;

    public ReviewM0ReviewJobExecutor(
            ReviewInputProvider inputProvider,
            ReviewReportReader reportReader,
            IncrementalReviewValidator incrementalValidator,
            DeterministicReviewEngine engine,
            ReviewReportAssembler assembler,
            ReviewReportStore reportStore,
            Clock clock
    ) {
        this.inputProvider = Objects.requireNonNull(inputProvider);
        this.reportReader = Objects.requireNonNull(reportReader);
        this.incrementalValidator = Objects.requireNonNull(incrementalValidator);
        this.engine = Objects.requireNonNull(engine);
        this.assembler = Objects.requireNonNull(assembler);
        this.reportStore = Objects.requireNonNull(reportStore);
        this.clock = Objects.requireNonNull(clock);
    }

    public void execute(ReviewJob leasedJob, ReviewM0JobStore jobStore, ReviewMode expectedMode) {
        try {
            requireLease(leasedJob);
            ReviewJobRequestEnvelope request = ReviewJobRequestEnvelope.readCanonical(leasedJob.requestJson());
            requireRequestBinding(leasedJob, request, expectedMode);
            CapturedReviewInput current = inputProvider.captureForExecution(
                    leasedJob.userId(), leasedJob.caseId(), request.request().checklistSnapshotId(),
                    request.request().uploadConfirmationId());
            if (!current.manifest().canonicalJson().equals(request.input().canonicalJson())
                    || !current.manifest().fingerprint().sha256().equals(leasedJob.inputFingerprint())) {
                throw new ReviewM0Exception(
                        expectedMode == ReviewMode.INCREMENTAL
                                ? ReviewM0ErrorCode.REVIEW_M0_INCREMENTAL_BASE_MISMATCH
                                : ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT,
                        "Formal review input changed after Job submission");
            }
            checkpoint(jobStore, leasedJob, "INPUT_VERIFIED", Map.of(
                    "inputFingerprint", leasedJob.inputFingerprint(),
                    "materialCount", current.manifest().materials().size()));
            List<FindingDraft> findings;
            if (expectedMode == ReviewMode.FULL) {
                findings = engine.evaluateFull(current);
            } else {
                PublishedReview base = reportReader.requireOwned(
                        leasedJob.userId(), leasedJob.caseId(), request.request().baseReportId());
                IncrementalPlan plan = incrementalValidator.validate(
                        base.inputManifest(), current.manifest(), request.request().changedMaterialIds());
                findings = engine.evaluateIncremental(current, base.findings(), plan);
            }
            checkpoint(jobStore, leasedJob, "REVIEW_COMPUTED", Map.of(
                    "findingCount", findings.size(),
                    "inputFingerprint", leasedJob.inputFingerprint()));
            ReviewJob latest = jobStore.findOwned(
                    leasedJob.userId(), leasedJob.caseId(), leasedJob.jobId()).orElseThrow();
            if (latest.status() == ReviewJobStatus.CANCEL_REQUESTED) return;
            ReportBundle bundle = assembler.assemble(latest, request, findings);
            reportStore.publishOwned(
                    leasedJob.userId(), leasedJob.leaseOwner(), clock.instant(), bundle);
        } catch (ReviewM0JobExecutionException error) {
            throw error;
        } catch (ReviewM0Exception error) {
            throw execution(error.errorCode(), retryable(error.errorCode()), safeMessage(error.errorCode()), error);
        } catch (RuntimeException error) {
            throw execution(
                    ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR, false,
                    "Deterministic Review M0 execution failed", error);
        }
    }

    private void checkpoint(
            ReviewM0JobStore jobStore,
            ReviewJob leasedJob,
            String key,
            Map<String, Object> value
    ) {
        String json = CanonicalJson.write(new LinkedHashMap<>(value));
        jobStore.saveCheckpoint(
                leasedJob.jobId(), leasedJob.leaseOwner(), key, json,
                CanonicalJson.sha256(value),
                Math.min(leasedJob.progressTotal(), "INPUT_VERIFIED".equals(key) ? 1 : leasedJob.progressTotal()),
                leasedJob.progressTotal(), clock.instant(), CHECKPOINT_LEASE_EXTENSION);
    }

    private static void requireLease(ReviewJob job) {
        if (job.status() != ReviewJobStatus.RUNNING || job.leaseOwner() == null) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                    "Formal review handler requires a leased RUNNING Job");
        }
    }

    private static void requireRequestBinding(
            ReviewJob job,
            ReviewJobRequestEnvelope request,
            ReviewMode expectedMode
    ) {
        ReviewJobType expectedType = expectedMode == ReviewMode.FULL
                ? ReviewJobType.FORMAL_REVIEW : ReviewJobType.INCREMENTAL_REVIEW;
        if (job.jobType() != expectedType
                || request.request().mode() != expectedMode
                || !request.request().checklistSnapshotId().equals(request.input().checklistSnapshotId())
                || !request.request().uploadConfirmationId().equals(request.input().uploadConfirmationId())
                || !request.input().fingerprint().sha256().equals(job.inputFingerprint())) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                    "Formal review Job request does not match its immutable input");
        }
    }

    private static boolean retryable(ReviewM0ErrorCode code) {
        return code == ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE
                || code == ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE
                || code == ReviewM0ErrorCode.REVIEW_M0_JOB_QUEUE_UNAVAILABLE;
    }

    private static String safeMessage(ReviewM0ErrorCode code) {
        return switch (code) {
            case REVIEW_M0_FILE_SERVICE_UNAVAILABLE -> "Owned file access is unavailable";
            case REVIEW_M0_STORAGE_UNAVAILABLE -> "Review storage is unavailable";
            case REVIEW_M0_INCREMENTAL_BASE_MISMATCH -> "Incremental review base does not match current input";
            case REVIEW_M0_UPLOAD_NOT_CONFIRMED -> "A current upload confirmation is required";
            case REVIEW_M0_CHECKLIST_NOT_CONFIRMED, REVIEW_M0_CHECKLIST_STALE_BASIS ->
                    "A current published checklist is required";
            case REVIEW_M0_VERSION_CONFLICT -> "Formal review input changed concurrently";
            default -> "Deterministic Review M0 execution failed";
        };
    }

    private static ReviewM0JobExecutionException execution(
            ReviewM0ErrorCode code, boolean retryable, String message, Throwable cause
    ) {
        return new ReviewM0JobExecutionException(code, retryable, message, cause);
    }
}
