package com.javaee.aiservice.review.m0.extraction;

import com.javaee.aiservice.review.m0.checklist.ChecklistDraftView;
import com.javaee.aiservice.review.m0.checklist.ChecklistService;
import com.javaee.aiservice.review.m0.job.ReviewM0JobExecutionException;
import com.javaee.aiservice.review.m0.job.ReviewM0JobHandler;
import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.job.ReviewM0RemoteCallStore;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.RemoteCallState;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobResultRef;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.privacy.RemoteConsentVerifier;
import com.javaee.aiservice.review.m0.privacy.RemoteSendAuthorization;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public class ChecklistExtractionJobHandler implements ReviewM0JobHandler {
    public static final String RESULT_TYPE = "CHECKLIST_DRAFT";

    private final ReviewCaseStore caseStore;
    private final ChecklistService checklistService;
    private final OfficialBasisContentProvider contentProvider;
    private final LocalChecklistCandidateExtractor localExtractor;
    private final RemoteConsentVerifier consentVerifier;
    private final ReviewM0RemoteCallStore remoteCallStore;
    private final RemoteCandidateClientFactory remoteClientFactory;
    private final Clock clock;

    public ChecklistExtractionJobHandler(
            ReviewCaseStore caseStore,
            ChecklistService checklistService,
            OfficialBasisContentProvider contentProvider,
            LocalChecklistCandidateExtractor localExtractor,
            RemoteConsentVerifier consentVerifier,
            ReviewM0RemoteCallStore remoteCallStore,
            RemoteCandidateClientFactory remoteClientFactory,
            Clock clock
    ) {
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.checklistService = Objects.requireNonNull(checklistService, "checklistService");
        this.contentProvider = Objects.requireNonNull(contentProvider, "contentProvider");
        this.localExtractor = Objects.requireNonNull(localExtractor, "localExtractor");
        this.consentVerifier = Objects.requireNonNull(consentVerifier, "consentVerifier");
        this.remoteCallStore = Objects.requireNonNull(remoteCallStore, "remoteCallStore");
        this.remoteClientFactory = remoteClientFactory;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ReviewJobType jobType() {
        return ReviewJobType.CHECKLIST_EXTRACTION;
    }

    @Override
    public void handle(ReviewJob leasedJob, ReviewM0JobStore jobStore) {
        Instant now = clock.instant();
        try {
            ChecklistExtractionRequest request = ChecklistExtractionRequest.read(leasedJob.requestJson());
            ReviewCase reviewCase = caseStore.findOwned(
                            leasedJob.userId(), leasedJob.caseId()).orElseThrow(() ->
                    new ReviewM0Exception(
                            ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND, "Review case was not found"));
            if (reviewCase.basisRevision() != request.basisRevision()) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_BASIS_REVISION_STALE,
                        "Checklist extraction basis revision is stale");
            }
            if (reviewCase.processingMode() != request.processingMode()) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                        "Checklist extraction mode does not match the case");
            }
            if (request.processingMode() == ProcessingMode.LOCAL_ONLY) {
                runLocal(leasedJob, request, jobStore, now);
            } else {
                runRemote(leasedJob, request, jobStore, now);
            }
        } catch (ReviewM0JobExecutionException error) {
            throw error;
        } catch (ReviewM0Exception error) {
            throw execution(error.errorCode(), false, safeMessage(error), null);
        } catch (RuntimeException error) {
            throw execution(
                    ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR,
                    false,
                    "Checklist extraction failed",
                    null);
        }
    }

    private void runLocal(
            ReviewJob job,
            ChecklistExtractionRequest request,
            ReviewM0JobStore jobStore,
            Instant now
    ) {
        List<OfficialBasisSegment> segments = List.copyOf(contentProvider.loadOwned(
                job.userId(), job.caseId(), request.basisRevision()));
        List<com.javaee.aiservice.review.m0.checklist.ChecklistCandidate> candidates =
                List.copyOf(localExtractor.extract(segments));
        jobStore.completeWithResult(job.jobId(), job.leaseOwner(), now, () -> {
            ChecklistDraftView draft = checklistService.replaceOfficialCandidatesOwned(
                    job.userId(), job.caseId(), request.basisRevision(), job.jobId(), candidates);
            return new ReviewJobResultRef(RESULT_TYPE, draft.checklistRevisionId());
        });
    }

    private void runRemote(
            ReviewJob job,
            ChecklistExtractionRequest request,
            ReviewM0JobStore jobStore,
            Instant now
    ) {
        RemoteSendAuthorization authorization = consentVerifier.authorizeForLogicalJob(
                job.userId(), job.caseId(), job.jobId(), job.leaseOwner(), request.consentId(),
                ReviewJobType.CHECKLIST_EXTRACTION, request.basisRevision(), now);
        if (authorization.disposition()
                == RemoteSendAuthorization.Disposition.RESPONSE_ALREADY_RECORDED) {
            ChecklistDraftView draft = checklistService.requireDraftForExtractionJobOwned(
                    job.userId(), job.caseId(), job.jobId());
            jobStore.completeWithResult(job.jobId(), job.leaseOwner(), now,
                    () -> new ReviewJobResultRef(RESULT_TYPE, draft.checklistRevisionId()));
            return;
        }
        if (remoteClientFactory == null) {
            remoteCallStore.markOutcomeUnknown(job.jobId(), job.leaseOwner(), now);
            throw execution(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_MODEL_FAILED,
                    false,
                    "No explicitly injected remote extraction client is available",
                    null);
        }
        byte[] payloadBytes = authorization.payloadBytes();
        if (!sha256(payloadBytes).equals(authorization.payloadDigest())) {
            remoteCallStore.markOutcomeUnknown(job.jobId(), job.leaseOwner(), now);
            throw execution(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE,
                    false,
                    "Authorized remote payload digest changed before send",
                    null);
        }
        RemoteExtractionResult result;
        try {
            RemoteCandidateClient client = Objects.requireNonNull(
                    remoteClientFactory.create(), "Explicit remote client factory returned null");
            result = Objects.requireNonNull(
                    client.extract(payloadBytes), "Explicit remote extraction client returned null");
        } catch (RemoteCandidateClientException error) {
            remoteCallStore.markOutcomeUnknown(job.jobId(), job.leaseOwner(), now);
            throw execution(
                    error.outcomeUnknown()
                            ? ReviewM0ErrorCode.REVIEW_M0_REMOTE_OUTCOME_UNKNOWN
                            : ReviewM0ErrorCode.REVIEW_M0_REMOTE_MODEL_FAILED,
                    false,
                    error.outcomeUnknown()
                            ? "Remote outcome is unknown and requires new authorization"
                            : "Remote extraction failed",
                    null);
        } catch (RuntimeException error) {
            remoteCallStore.markOutcomeUnknown(job.jobId(), job.leaseOwner(), now);
            throw execution(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_OUTCOME_UNKNOWN,
                    false,
                    "Remote outcome is unknown and requires new authorization",
                    null);
        }
        String responseDigest = sha256(result.responseBytes());
        String providerRequestId = safeProviderRequestId(result.providerRequestId());
        try {
            jobStore.completeWithResult(job.jobId(), job.leaseOwner(), now, () -> {
                ChecklistDraftView draft = checklistService.replaceOfficialCandidatesOwned(
                        job.userId(), job.caseId(), request.basisRevision(), job.jobId(), result.candidates());
                remoteCallStore.recordResponse(
                        job.jobId(), job.leaseOwner(), providerRequestId, responseDigest, now);
                return new ReviewJobResultRef(RESULT_TYPE, draft.checklistRevisionId());
            });
        } catch (RuntimeException error) {
            if (remoteCallStore.findOwned(job.userId(), job.caseId(), job.jobId())
                    .filter(call -> call.state() == RemoteCallState.SEND_INTENT).isPresent()) {
                remoteCallStore.recordResponse(
                        job.jobId(), job.leaseOwner(), providerRequestId, responseDigest, now);
            }
            throw execution(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_MODEL_FAILED,
                    false,
                    "Remote extraction response could not produce a valid candidate draft",
                    null);
        }
    }

    private static String safeProviderRequestId(String providerRequestId) {
        if (providerRequestId == null || !providerRequestId.matches("[A-Za-z0-9._:-]{1,255}")) {
            return null;
        }
        return providerRequestId;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String safeMessage(ReviewM0Exception error) {
        return switch (error.errorCode()) {
            case REVIEW_M0_BASIS_REVISION_STALE -> "Checklist extraction basis revision is stale";
            case REVIEW_M0_REMOTE_PREVIEW_STALE -> "Remote preview authorization is stale";
            case REVIEW_M0_REMOTE_CONSENT_REQUIRED -> "Matching remote consent is required";
            case REVIEW_M0_REMOTE_CONSENT_CONSUMED -> "Remote consent was already consumed";
            case REVIEW_M0_REMOTE_OUTCOME_UNKNOWN -> "Remote outcome is unknown and requires new authorization";
            default -> "Checklist extraction request failed";
        };
    }

    private static ReviewM0JobExecutionException execution(
            ReviewM0ErrorCode code, boolean retryable, String message, Throwable cause
    ) {
        return new ReviewM0JobExecutionException(code, retryable, message, cause);
    }
}
