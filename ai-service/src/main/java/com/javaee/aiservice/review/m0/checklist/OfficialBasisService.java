package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.OfficialBasis;
import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.OfficialBasisStatus;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public class OfficialBasisService {
    private final ReviewCaseStore caseStore;
    private final JdbcChecklistStore checklistStore;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OfficialBasisService(
            ReviewCaseStore caseStore,
            JdbcChecklistStore checklistStore,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.checklistStore = Objects.requireNonNull(checklistStore, "checklistStore");
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OfficialBasis attachOwned(
            String ownerId,
            String caseId,
            long expectedCaseVersion,
            OfficialBasisAttachment attachment
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase reviewCase = requireCase(ownerId, caseId);
            requireVersion(reviewCase, expectedCaseVersion);
            Instant now = clock.instant();
            OfficialBasis basis = checklistStore.insertBasisOwned(
                    ownerId, caseId, attachment, reviewCase.basisRevision() + 1, now);
            caseStore.advanceRevisionOwned(
                    ownerId, caseId, expectedCaseVersion, RevisionKind.BASIS, "OFFICIAL_BASIS_ATTACHED");
            return basis;
        }));
    }

    public OfficialBasis reclassifyOwned(
            String ownerId,
            String caseId,
            String basisId,
            long expectedCaseVersion,
            long expectedBasisVersion,
            OfficialBasisRole role
    ) {
        if (role == null) {
            throw invalid("Official basis role is required");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase reviewCase = requireCase(ownerId, caseId);
            requireVersion(reviewCase, expectedCaseVersion);
            OfficialBasis current = checklistStore.findBasisOwned(ownerId, caseId, basisId)
                    .orElseThrow(OfficialBasisService::resourceNotFound);
            if (current.version() != expectedBasisVersion) {
                throw conflict("Official basis changed concurrently");
            }
            if (current.status() == OfficialBasisStatus.DETACHED) {
                throw conflict("Detached official basis cannot be reclassified");
            }
            if (current.role() == role) {
                return current;
            }
            OfficialBasis updated = checklistStore.updateBasisRoleOwned(
                    ownerId, caseId, basisId, expectedBasisVersion, role, clock.instant());
            caseStore.advanceRevisionOwned(
                    ownerId, caseId, expectedCaseVersion, RevisionKind.BASIS, "OFFICIAL_BASIS_RECLASSIFIED");
            return updated;
        }));
    }

    public OfficialBasis markReadyOwned(
            String ownerId, String caseId, String basisId, long expectedBasisVersion
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            OfficialBasis current = checklistStore.findBasisOwned(ownerId, caseId, basisId)
                    .orElseThrow(OfficialBasisService::resourceNotFound);
            if (current.version() != expectedBasisVersion) {
                throw conflict("Official basis changed concurrently");
            }
            if (current.status() == OfficialBasisStatus.READY) {
                return current;
            }
            if (current.status() != OfficialBasisStatus.ATTACHED) {
                throw conflict("Official basis cannot transition to READY");
            }
            return checklistStore.updateBasisStatusOwned(
                    ownerId, caseId, basisId, expectedBasisVersion,
                    OfficialBasisStatus.ATTACHED, OfficialBasisStatus.READY,
                    null, null, null, clock.instant());
        }));
    }

    public OfficialBasis markCannotConfirmOwned(
            String ownerId,
            String caseId,
            String basisId,
            long expectedBasisVersion,
            CannotConfirmReason reason,
            String failureStage
    ) {
        if (reason == null || failureStage == null || failureStage.isBlank() || failureStage.length() > 128) {
            throw invalid("Official basis diagnostic metadata is invalid");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            OfficialBasis current = checklistStore.findBasisOwned(ownerId, caseId, basisId)
                    .orElseThrow(OfficialBasisService::resourceNotFound);
            if (current.version() != expectedBasisVersion) {
                throw conflict("Official basis changed concurrently");
            }
            if (current.status() != OfficialBasisStatus.ATTACHED) {
                throw conflict("Official basis cannot transition to CANNOT_CONFIRM");
            }
            Instant now = clock.instant();
            OfficialBasis updated = checklistStore.updateBasisStatusOwned(
                    ownerId, caseId, basisId, expectedBasisVersion,
                    OfficialBasisStatus.ATTACHED, OfficialBasisStatus.CANNOT_CONFIRM,
                    reason, failureStage, null, now);
            checklistStore.insertBasisDiagnosticOwned(ownerId, caseId, basisId, reason, failureStage, now);
            return updated;
        }));
    }

    public OfficialBasis detachOwned(
            String ownerId,
            String caseId,
            String basisId,
            long expectedCaseVersion,
            long expectedBasisVersion
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase reviewCase = requireCase(ownerId, caseId);
            requireVersion(reviewCase, expectedCaseVersion);
            OfficialBasis current = checklistStore.findBasisOwned(ownerId, caseId, basisId)
                    .orElseThrow(OfficialBasisService::resourceNotFound);
            if (current.version() != expectedBasisVersion) {
                throw conflict("Official basis changed concurrently");
            }
            if (current.status() == OfficialBasisStatus.DETACHED) {
                return current;
            }
            Instant now = clock.instant();
            OfficialBasis detached = checklistStore.updateBasisStatusOwned(
                    ownerId, caseId, basisId, expectedBasisVersion, current.status(),
                    OfficialBasisStatus.DETACHED, null, null,
                    reviewCase.basisRevision() + 1, now);
            caseStore.advanceRevisionOwned(
                    ownerId, caseId, expectedCaseVersion, RevisionKind.BASIS, "OFFICIAL_BASIS_DETACHED");
            return detached;
        }));
    }

    public List<OfficialBasis> listOwned(String ownerId, String caseId) {
        return checklistStore.listBasesOwned(ownerId, caseId);
    }

    private ReviewCase requireCase(String ownerId, String caseId) {
        return caseStore.findOwned(ownerId, caseId).orElseThrow(() ->
                new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND, "Review case was not found"));
    }

    private static void requireVersion(ReviewCase reviewCase, long expectedVersion) {
        if (reviewCase.version() != expectedVersion) {
            throw conflict("Review case changed concurrently");
        }
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }

    private static ReviewM0Exception conflict(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT, message);
    }

    private static ReviewM0Exception resourceNotFound() {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                "Official basis was not found");
    }
}
