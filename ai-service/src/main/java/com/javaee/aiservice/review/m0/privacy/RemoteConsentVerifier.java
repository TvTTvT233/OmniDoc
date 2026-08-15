package com.javaee.aiservice.review.m0.privacy;

import com.javaee.aiservice.review.m0.checklist.M0IdGenerator;
import com.javaee.aiservice.review.m0.extraction.ChecklistExtractionRequest;
import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.job.ReviewM0RemoteCallStore;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.RemoteCall;
import com.javaee.aiservice.review.m0.model.RemoteCallState;
import com.javaee.aiservice.review.m0.model.RemoteConsent;
import com.javaee.aiservice.review.m0.model.RemotePreview;
import com.javaee.aiservice.review.m0.model.RemotePreviewStatus;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;

import java.time.Instant;
import java.util.Objects;

public class RemoteConsentVerifier {
    private final ReviewCaseStore caseStore;
    private final ReviewM0JobStore jobStore;
    private final JdbcRemotePrivacyStore privacyStore;
    private final ReviewM0RemoteCallStore remoteCallStore;
    private final M0IdGenerator ids;

    public RemoteConsentVerifier(
            ReviewCaseStore caseStore,
            ReviewM0JobStore jobStore,
            JdbcRemotePrivacyStore privacyStore,
            ReviewM0RemoteCallStore remoteCallStore,
            M0IdGenerator ids
    ) {
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore");
        this.privacyStore = Objects.requireNonNull(privacyStore, "privacyStore");
        this.remoteCallStore = Objects.requireNonNull(remoteCallStore, "remoteCallStore");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public RemoteSendAuthorization authorizeForLogicalJob(
            String ownerId,
            String caseId,
            String jobId,
            String workerId,
            String consentId,
            ReviewJobType operation,
            long revision,
            Instant now
    ) {
        ReviewCase reviewCase = caseStore.findOwned(ownerId, caseId).orElseThrow(() ->
                notFound(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND, "Review case was not found"));
        ReviewJob job = jobStore.findOwned(ownerId, caseId, jobId).orElseThrow(() ->
                notFound(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, "Review job was not found"));
        ChecklistExtractionRequest targetRequest = ChecklistExtractionRequest.read(job.requestJson());
        ConsentPreview consentPreview = privacyStore.findConsentOwned(ownerId, caseId, consentId)
                .orElseThrow(() -> notFound(
                        ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                        "Remote consent was not found"));
        RemoteConsent consent = consentPreview.consent();
        RemotePreview preview = consentPreview.preview().preview();
        if (reviewCase.processingMode() != ProcessingMode.REMOTE_MODEL
                || targetRequest.processingMode() != ProcessingMode.REMOTE_MODEL
                || !Objects.equals(targetRequest.consentId(), consentId)
                || job.status() != ReviewJobStatus.RUNNING || job.jobType() != ReviewJobType.CHECKLIST_EXTRACTION
                || job.jobType() != operation || consent.operation() != operation
                || preview.operation() != operation) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED,
                    "Remote consent does not authorize the target logical job operation");
        }
        if (!Objects.equals(job.leaseOwner(), workerId) || job.leaseExpiresAt() == null
                || !job.leaseExpiresAt().isAfter(now)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                    "Worker does not hold the target logical job lease");
        }
        if (!consentPreview.currentConfirmation()
                || targetRequest.basisRevision() != revision || revision != reviewCase.basisRevision()
                || consent.basisRevision() != revision
                || preview.basisRevision() != revision || preview.status() != RemotePreviewStatus.CONFIRMED) {
            throw stale();
        }
        byte[] payload = RemotePayloads.payloadBytes(consentPreview.preview().segments());
        String digest = RemotePayloads.sha256(payload);
        if (!digest.equals(consent.payloadDigest()) || !digest.equals(preview.payloadDigest())) {
            throw stale();
        }
        if (consent.consumedByJobId() != null && !jobId.equals(consent.consumedByJobId())) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_CONSUMED,
                    "Remote consent was already consumed by another logical job");
        }
        RemoteCall existing = remoteCallStore.findOwned(ownerId, caseId, jobId).orElse(null);
        if (existing != null) {
            if (existing.state() == RemoteCallState.RESPONSE_RECORDED) {
                return new RemoteSendAuthorization(
                        RemoteSendAuthorization.Disposition.RESPONSE_ALREADY_RECORDED,
                        null, digest, existing);
            }
            if (existing.state() == RemoteCallState.SEND_INTENT) {
                remoteCallStore.markOutcomeUnknown(jobId, workerId, now);
            }
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_OUTCOME_UNKNOWN,
                    "Remote outcome is unknown and cannot be resent automatically");
        }
        RemoteCall intent = new RemoteCall(
                ids.next("remotecall_"), jobId, consentId, digest,
                RemoteCallState.SEND_INTENT, null, null, now, now);
        RemoteCall persisted = remoteCallStore.beginSendIntent(intent, workerId, now);
        if (persisted.state() != RemoteCallState.SEND_INTENT
                || !persisted.requestDigest().equals(digest)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_OUTCOME_UNKNOWN,
                    "Remote send intent cannot authorize a new send");
        }
        return new RemoteSendAuthorization(
                RemoteSendAuthorization.Disposition.SEND_AUTHORIZED,
                payload, digest, persisted);
    }

    private static ReviewM0Exception stale() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE,
                "Remote consent does not match the current preview revision or digest");
    }

    private static ReviewM0Exception notFound(ReviewM0ErrorCode code, String message) {
        return new ReviewM0Exception(code, message);
    }
}
