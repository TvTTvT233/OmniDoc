package com.javaee.aiservice.review.m0.privacy;

import com.javaee.aiservice.review.m0.checklist.JdbcChecklistStore;
import com.javaee.aiservice.review.m0.model.OfficialBasis;
import com.javaee.aiservice.review.m0.model.OfficialBasisStatus;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.RemoteConsent;
import com.javaee.aiservice.review.m0.model.RemotePreviewStatus;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class RemotePreviewService {
    private final ReviewCaseStore caseStore;
    private final JdbcChecklistStore checklistStore;
    private final JdbcRemotePrivacyStore privacyStore;
    private final Clock clock;

    public RemotePreviewService(
            ReviewCaseStore caseStore,
            JdbcChecklistStore checklistStore,
            JdbcRemotePrivacyStore privacyStore,
            Clock clock
    ) {
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.checklistStore = Objects.requireNonNull(checklistStore, "checklistStore");
        this.privacyStore = Objects.requireNonNull(privacyStore, "privacyStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RemotePreviewView createOwned(
            String ownerId,
            String caseId,
            ReviewJobType operation,
            long basisRevision,
            List<RemotePreviewSegment> explicitlyRedactedSegments
    ) {
        ReviewCase reviewCase = requireRemoteCase(ownerId, caseId);
        if (operation != ReviewJobType.CHECKLIST_EXTRACTION) {
            throw invalid("Remote preview operation is unsupported");
        }
        if (reviewCase.basisRevision() != basisRevision) {
            throw stale();
        }
        List<RemotePreviewSegment> segments = List.copyOf(explicitlyRedactedSegments);
        validateSegments(ownerId, caseId, segments);
        return privacyStore.createOwned(
                ownerId, caseId, operation, basisRevision, segments, clock.instant());
    }

    public RemotePreviewView editOwned(
            String ownerId,
            String caseId,
            String previewId,
            long expectedVersion,
            List<RemotePreviewSegment> editedSegments
    ) {
        requireRemoteCase(ownerId, caseId);
        List<RemotePreviewSegment> segments = List.copyOf(editedSegments);
        validateSegments(ownerId, caseId, segments);
        return privacyStore.updateOwned(
                ownerId, caseId, previewId, expectedVersion, segments, clock.instant());
    }

    public RemoteConsent confirmOwned(
            String ownerId,
            String caseId,
            String previewId,
            long expectedVersion,
            String acknowledgedPayloadDigest
    ) {
        ReviewCase reviewCase = requireRemoteCase(ownerId, caseId);
        RemotePreviewView preview = privacyStore.findPreviewOwned(ownerId, caseId, previewId)
                .orElseThrow(RemotePreviewService::resourceNotFound);
        if (preview.preview().basisRevision() != reviewCase.basisRevision()) {
            throw stale();
        }
        if (preview.preview().status() != RemotePreviewStatus.READY_FOR_CONFIRMATION) {
            throw stale();
        }
        requireSendable(preview.segments());
        return privacyStore.confirmOwned(
                ownerId, caseId, previewId, expectedVersion, acknowledgedPayloadDigest, clock.instant());
    }

    public RemotePreviewView requireOwned(String ownerId, String caseId, String previewId) {
        requireRemoteCase(ownerId, caseId);
        return privacyStore.findPreviewOwned(ownerId, caseId, previewId)
                .orElseThrow(RemotePreviewService::resourceNotFound);
    }

    private void validateSegments(String ownerId, String caseId, List<RemotePreviewSegment> segments) {
        Set<String> segmentIds = new HashSet<>();
        Map<String, OfficialBasis> activeBases = new HashMap<>();
        for (OfficialBasis basis : checklistStore.listBasesOwned(ownerId, caseId)) {
            if (basis.status() != OfficialBasisStatus.DETACHED) {
                activeBases.put(basis.basisId(), basis);
            }
        }
        for (RemotePreviewSegment segment : segments) {
            if (!segmentIds.add(segment.segmentId()) || !activeBases.containsKey(segment.basisId())) {
                throw invalid("Remote preview contains a duplicate or unavailable source segment");
            }
        }
    }

    private static void requireSendable(List<RemotePreviewSegment> segments) {
        boolean sendable = segments.stream()
                .anyMatch(segment -> segment.included() && !segment.redactedText().isBlank());
        if (!sendable) {
            throw invalid("Remote preview must contain at least one included redacted segment");
        }
    }

    private ReviewCase requireRemoteCase(String ownerId, String caseId) {
        ReviewCase reviewCase = caseStore.findOwned(ownerId, caseId).orElseThrow(() ->
                new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND,
                        "Review case was not found"));
        if (reviewCase.processingMode() != ProcessingMode.REMOTE_MODEL) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED,
                    "Remote preview requires an explicitly remote case");
        }
        return reviewCase;
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }

    private static ReviewM0Exception stale() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE,
                "Remote preview does not match the current revision or state");
    }

    private static ReviewM0Exception resourceNotFound() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                "Remote preview was not found");
    }
}
