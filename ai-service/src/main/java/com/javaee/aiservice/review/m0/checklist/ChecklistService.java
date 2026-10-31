package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.OfficialBasis;
import com.javaee.aiservice.review.m0.model.OfficialBasisStatus;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.model.RequirementOrigin;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewM0StateMachines;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ChecklistService {
    private final ReviewCaseStore caseStore;
    private final JdbcChecklistStore checklistStore;
    private final TransactionTemplate transactions;
    private final M0IdGenerator ids;
    private final Clock clock;

    public ChecklistService(
            ReviewCaseStore caseStore,
            JdbcChecklistStore checklistStore,
            PlatformTransactionManager transactionManager,
            M0IdGenerator ids,
            Clock clock
    ) {
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.checklistStore = Objects.requireNonNull(checklistStore, "checklistStore");
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ChecklistDraftView replaceOfficialCandidatesOwned(
            String ownerId,
            String caseId,
            long basisRevision,
            String extractionJobId,
            List<ChecklistCandidate> candidates
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase reviewCase = requireCase(ownerId, caseId);
            requireBasisRevision(reviewCase, basisRevision);
            Map<String, OfficialBasis> bases = readyBasesForCandidate(ownerId, caseId);
            List<ChecklistDraftItem> items = new ArrayList<>();
            checklistStore.findLatestRevisionOwned(ownerId, caseId).ifPresent(previous ->
                    previous.items().stream()
                            .filter(item -> item.origin() == RequirementOrigin.USER_ADDED)
                            .forEach(items::add));
            for (ChecklistCandidate candidate : List.copyOf(candidates)) {
                validateCandidate(candidate, bases);
                items.add(new ChecklistDraftItem(
                        ids.next("reqmt_"), candidate.displayName(), candidate.enabled(),
                        candidate.applicability(), candidate.minimumCount(), candidate.fileNameRule(),
                        RequirementOrigin.OFFICIAL_EXTRACTED, RequirementAuthorship.EXTRACTED_TEXT,
                        candidate.sourceRefs(), 0));
            }
            return checklistStore.saveDraftOwned(
                    ownerId, caseId, basisRevision, ownerId, extractionJobId, items, clock.instant());
        }));
    }

    public ChecklistDraftView addUserRequirementOwned(
            String ownerId,
            String caseId,
            String displayName,
            boolean enabled,
            RequirementApplicability applicability,
            int minimumCount,
            FileNameRule fileNameRule
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase reviewCase = requireCase(ownerId, caseId);
            List<ChecklistDraftItem> items = editableItems(ownerId, caseId);
            items.add(new ChecklistDraftItem(
                    ids.next("reqmt_"), displayName, enabled, applicability, minimumCount, fileNameRule,
                    RequirementOrigin.USER_ADDED, RequirementAuthorship.USER_REWRITTEN, List.of(), 0));
            return checklistStore.saveDraftOwned(
                    ownerId, caseId, reviewCase.basisRevision(), ownerId, null, items, clock.instant());
        }));
    }

    public ChecklistDraftView editRequirementOwned(
            String ownerId,
            String caseId,
            String requirementId,
            ChecklistItemEdit edit
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase reviewCase = requireCase(ownerId, caseId);
            List<ChecklistDraftItem> items = editableItems(ownerId, caseId);
            int index = -1;
            for (int current = 0; current < items.size(); current++) {
                if (items.get(current).requirementId().equals(requirementId)) {
                    index = current;
                    break;
                }
            }
            if (index < 0) {
                throw resourceNotFound("Checklist requirement was not found");
            }
            ChecklistDraftItem previous = items.get(index);
            if (previous.itemVersion() != edit.expectedItemVersion()) {
                throw conflict("Checklist requirement changed concurrently");
            }
            boolean rewritten = previous.origin() == RequirementOrigin.USER_ADDED
                    || !previous.displayName().equals(edit.displayName())
                    || previous.applicability() != edit.applicability()
                    || previous.minimumCount() != edit.minimumCount()
                    || !previous.fileNameRule().equals(edit.fileNameRule());
            items.set(index, new ChecklistDraftItem(
                    previous.requirementId(), edit.displayName(), edit.enabled(), edit.applicability(),
                    edit.minimumCount(), edit.fileNameRule(), previous.origin(),
                    rewritten ? RequirementAuthorship.USER_REWRITTEN : previous.authorship(),
                    previous.sourceRefs(), previous.itemVersion() + 1));
            return checklistStore.saveDraftOwned(
                    ownerId, caseId, reviewCase.basisRevision(), ownerId, null, items, clock.instant());
        }));
    }

    public ChecklistSnapshot publishOwned(
            String ownerId,
            String caseId,
            long expectedCaseVersion,
            long expectedChecklistRevision,
            Set<String> acknowledgedRequirementIds
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase reviewCase = requireCase(ownerId, caseId);
            if (reviewCase.version() != expectedCaseVersion) {
                throw conflict("Review case changed concurrently");
            }
            ChecklistDraftView draft = checklistStore.findLatestRevisionOwned(ownerId, caseId)
                    .orElseThrow(() -> new ReviewM0Exception(
                            ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED,
                            "A candidate checklist draft is required"));
            if (draft.lifecycle() != ChecklistLifecycle.CANDIDATE_DRAFT) {
                throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED,
                        "The latest checklist revision must be a candidate before publication");
            }
            if (draft.revision() != expectedChecklistRevision) {
                throw conflict("Checklist draft revision changed concurrently");
            }
            requireBasisRevision(reviewCase, draft.basisRevision());
            Map<String, OfficialBasis> readyBases = currentReadyBases(ownerId, caseId);
            Set<String> enabledIds = new HashSet<>();
            List<ChecklistDraftItem> publishedItems = new ArrayList<>();
            for (ChecklistDraftItem item : draft.items()) {
                if (!item.enabled()) {
                    continue;
                }
                enabledIds.add(item.requirementId());
                if (item.applicability() == RequirementApplicability.NEEDS_USER_DECISION) {
                    throw new ReviewM0Exception(
                            ReviewM0ErrorCode.REVIEW_M0_CONDITIONAL_UNRESOLVED,
                            "Checklist contains an unresolved conditional requirement");
                }
                validatePublishableItem(item, readyBases);
                publishedItems.add(item);
            }
            Set<String> acknowledged = acknowledgedRequirementIds == null
                    ? Set.of() : Set.copyOf(acknowledgedRequirementIds);
            if (!enabledIds.equals(acknowledged)) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_ACK_MISMATCH,
                        "Every enabled checklist requirement must be acknowledged exactly once");
            }
            Instant now = clock.instant();
            String snapshotJson = ChecklistJson.snapshotJson(
                    caseId, reviewCase.basisRevision(), draft.revision(), publishedItems);
            ChecklistSnapshot snapshot = new ChecklistSnapshot(
                    ids.next("clsnap_"), caseId, draft.checklistRevisionId(),
                    reviewCase.basisRevision(), draft.revision(), snapshotJson,
                    CanonicalJson.sha256(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                            readSnapshotTree(snapshotJson), Object.class)),
                    ChecklistLifecycle.CONFIRMED, ownerId, now, null);
            return caseStore.publishChecklistSnapshotOwned(
                    ownerId, caseId, expectedCaseVersion, snapshot, "CHECKLIST_SNAPSHOT_PUBLISHED");
        }));
    }

    public ChecklistSnapshot requireCurrentFormalSnapshotOwned(
            String ownerId, String caseId, String checklistSnapshotId
    ) {
        ReviewCase reviewCase = requireCase(ownerId, caseId);
        if (!Objects.equals(reviewCase.currentChecklistSnapshotId(), checklistSnapshotId)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED,
                    "Formal review requires the current checklist snapshot");
        }
        ChecklistSnapshot snapshot = caseStore.findChecklistSnapshotOwned(ownerId, caseId, checklistSnapshotId)
                .orElseThrow(() -> resourceNotFound("Checklist snapshot was not found"));
        ReviewM0StateMachines.requireFormalChecklist(snapshot.lifecycle());
        if (snapshot.basisRevision() != reviewCase.basisRevision()) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_STALE_BASIS,
                    "Checklist snapshot is stale after official basis changes");
        }
        return snapshot;
    }

    public ChecklistDraftView requireLatestDraftOwned(String ownerId, String caseId) {
        requireCase(ownerId, caseId);
        return checklistStore.findLatestRevisionOwned(ownerId, caseId)
                .orElseThrow(() -> resourceNotFound("Checklist draft was not found"));
    }

    public ChecklistDraftView requireDraftForExtractionJobOwned(
            String ownerId, String caseId, String extractionJobId
    ) {
        requireCase(ownerId, caseId);
        return checklistStore.findDraftByExtractionJobOwned(ownerId, caseId, extractionJobId)
                .orElseThrow(() -> resourceNotFound("Checklist extraction result was not found"));
    }

    private List<ChecklistDraftItem> editableItems(String ownerId, String caseId) {
        return new ArrayList<>(checklistStore.findLatestRevisionOwned(ownerId, caseId)
                .map(ChecklistDraftView::items).orElseGet(List::of));
    }

    private Map<String, OfficialBasis> currentReadyBases(String ownerId, String caseId) {
        Map<String, OfficialBasis> ready = new HashMap<>();
        for (OfficialBasis basis : checklistStore.listBasesOwned(ownerId, caseId)) {
            if (basis.status() == OfficialBasisStatus.DETACHED) {
                continue;
            }
            if (basis.status() != OfficialBasisStatus.READY) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_BASIS_CANNOT_CONFIRM,
                        "All attached official bases must be reliably processed before checklist publication");
            }
            ready.put(basis.basisId(), basis);
        }
        return ready;
    }

    private Map<String, OfficialBasis> readyBasesForCandidate(String ownerId, String caseId) {
        Map<String, OfficialBasis> ready = new HashMap<>();
        for (OfficialBasis basis : checklistStore.listBasesOwned(ownerId, caseId)) {
            if (basis.status() == OfficialBasisStatus.READY) {
                ready.put(basis.basisId(), basis);
            }
        }
        return ready;
    }

    private static void validateCandidate(ChecklistCandidate candidate, Map<String, OfficialBasis> readyBases) {
        if (candidate == null) {
            throw invalid("Candidate checklist item is required");
        }
        ChecklistDraftItem probe = new ChecklistDraftItem(
                "candidate-validation", candidate.displayName(), candidate.enabled(), candidate.applicability(),
                candidate.minimumCount(), candidate.fileNameRule(), RequirementOrigin.OFFICIAL_EXTRACTED,
                RequirementAuthorship.EXTRACTED_TEXT, candidate.sourceRefs(), 0);
        validatePublishableItem(probe, readyBases);
    }

    private static void validatePublishableItem(
            ChecklistDraftItem item, Map<String, OfficialBasis> readyBases
    ) {
        RegexSafetyValidator.requirePublishable(item.fileNameRule());
        if (item.origin() == RequirementOrigin.OFFICIAL_EXTRACTED) {
            if (item.sourceRefs().isEmpty()) {
                throw invalid("Officially extracted requirement lacks a reliable source reference");
            }
            for (ChecklistSourceRef sourceRef : item.sourceRefs()) {
                if (!readyBases.containsKey(sourceRef.basisId())) {
                    throw invalid("Officially extracted requirement references a stale or unavailable basis");
                }
            }
        } else if (!item.sourceRefs().isEmpty()) {
            throw invalid("User-added requirement cannot contain official source references");
        }
    }

    private ReviewCase requireCase(String ownerId, String caseId) {
        return caseStore.findOwned(ownerId, caseId).orElseThrow(() ->
                new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND, "Review case was not found"));
    }

    private static void requireBasisRevision(ReviewCase reviewCase, long basisRevision) {
        if (reviewCase.basisRevision() != basisRevision) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_BASIS_REVISION_STALE,
                    "Checklist operation does not match the current official basis revision");
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode readSnapshotTree(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw invalid("Checklist snapshot cannot be encoded");
        }
    }

    private static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }

    private static ReviewM0Exception conflict(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT, message);
    }

    private static ReviewM0Exception resourceNotFound(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, message);
    }
}
