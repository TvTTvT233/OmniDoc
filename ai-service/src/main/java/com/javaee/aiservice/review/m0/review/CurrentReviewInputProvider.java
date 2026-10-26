package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.checklist.ChecklistService;
import com.javaee.aiservice.review.m0.checklist.JdbcChecklistStore;
import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;
import com.javaee.aiservice.review.m0.material.MaterialLifecycleService;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.DiagnosticSubjectType;
import com.javaee.aiservice.review.m0.model.InputDiagnostic;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.OfficialBasis;
import com.javaee.aiservice.review.m0.model.OfficialBasisStatus;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;
import com.javaee.aiservice.review.m0.model.SubmissionSourceStatus;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/** Production adapter over the frozen M0-02/M0-03/M0-04 owner-aware read boundaries. */
public final class CurrentReviewInputProvider implements ReviewInputProvider {
    private final ReviewCaseStore caseStore;
    private final ChecklistService checklistService;
    private final JdbcChecklistStore checklistStore;
    private final MaterialLifecycleService materialService;
    private final String engineVersion;

    public CurrentReviewInputProvider(
            ReviewCaseStore caseStore,
            ChecklistService checklistService,
            JdbcChecklistStore checklistStore,
            MaterialLifecycleService materialService,
            String engineVersion
    ) {
        this.caseStore = Objects.requireNonNull(caseStore);
        this.checklistService = Objects.requireNonNull(checklistService);
        this.checklistStore = Objects.requireNonNull(checklistStore);
        this.materialService = Objects.requireNonNull(materialService);
        this.engineVersion = requireEngineVersion(engineVersion);
    }

    @Override
    public CapturedReviewInput captureForSubmission(
            String ownerId, String caseId, String checklistSnapshotId, String uploadConfirmationId
    ) {
        return capture(ownerId, caseId, checklistSnapshotId, uploadConfirmationId,
                (owner, ownedCase) -> materialService.getSnapshotOwned(owner, ownedCase));
    }

    @Override
    public CapturedReviewInput captureForExecution(
            String ownerId, String caseId, String checklistSnapshotId, String uploadConfirmationId
    ) {
        return capture(ownerId, caseId, checklistSnapshotId, uploadConfirmationId,
                (owner, ownedCase) -> materialService.requireReviewReadyOwned(owner, ownedCase));
    }

    private CapturedReviewInput capture(
            String ownerId,
            String caseId,
            String checklistSnapshotId,
            String uploadConfirmationId,
            BiFunction<String, String, MaterialCollectionSnapshot> materialReader
    ) {
        ReviewCase before = requireCase(ownerId, caseId);
        FormalChecklist checklist = FormalChecklist.from(
                checklistService.requireCurrentFormalSnapshotOwned(ownerId, caseId, checklistSnapshotId));
        if (checklist.basisRevision() != before.basisRevision()) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_STALE_BASIS,
                    "Checklist snapshot is stale after official basis changes");
        }
        MaterialCollectionSnapshot raw = materialReader.apply(ownerId, caseId);
        MaterialCollectionSnapshot active = activeSnapshot(raw);
        UploadConfirmation upload = requireUpload(
                ownerId, caseId, checklistSnapshotId, uploadConfirmationId, active);
        List<OfficialBasis> bases = checklistStore.listBasesOwned(ownerId, caseId).stream()
                .filter(item -> item.status() != OfficialBasisStatus.DETACHED)
                .toList();
        if (bases.stream().anyMatch(item -> item.status() != OfficialBasisStatus.READY)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_BASIS_CANNOT_CONFIRM,
                    "Current official basis input is not ready for formal review");
        }
        ReviewCase after = requireCase(ownerId, caseId);
        if (!sameInput(before, after, checklistSnapshotId, active)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT,
                    "Review input changed while its immutable snapshot was captured");
        }
        ReviewInputManifest manifest = manifest(after, checklist, active, upload, bases);
        return new CapturedReviewInput(checklist, active, manifest);
    }

    private ReviewInputManifest manifest(
            ReviewCase reviewCase,
            FormalChecklist checklist,
            MaterialCollectionSnapshot snapshot,
            UploadConfirmation upload,
            List<OfficialBasis> bases
    ) {
        List<ReviewInputManifest.BasisFact> basisFacts = bases.stream()
                .map(item -> new ReviewInputManifest.BasisFact(item.basisId(), item.role(), item.fileDigest()))
                .toList();
        List<ReviewInputManifest.SourceFact> sourceFacts = snapshot.sources().stream()
                .map(item -> new ReviewInputManifest.SourceFact(
                        item.sourceId(), item.formalFileType(), item.fileDigest(), item.fileSizeBytes()))
                .toList();
        List<ReviewInputManifest.MaterialFact> materialFacts = snapshot.materials().stream()
                .map(item -> new ReviewInputManifest.MaterialFact(
                        item.materialId(), item.sourceId(), item.kind(), item.normalizedEntryPath(),
                        item.entryIndex(), item.contentDigest(), item.formalFileType(),
                        item.submissionFileName(), item.status(), item.cannotConfirmReason(), item.failureStage()))
                .toList();
        List<ReviewInputManifest.AssignmentFact> assignmentFacts = snapshot.assignments().stream()
                .filter(item -> item.status() == AssignmentStatus.USER_CONFIRMED)
                .map(item -> new ReviewInputManifest.AssignmentFact(
                        item.materialId(), item.requirementId(), item.status()))
                .toList();
        List<ReviewInputManifest.DiagnosticFact> diagnosticFacts = snapshot.diagnostics().stream()
                .filter(item -> item.subjectType() != DiagnosticSubjectType.OFFICIAL_BASIS)
                .map(item -> new ReviewInputManifest.DiagnosticFact(
                        item.diagnosticId(), item.subjectType(), subjectId(item), item.cannotConfirmReason(),
                        item.failureStage(), locatorDigest(item)))
                .toList();
        return new ReviewInputManifest(
                FormalChecklist.CONTRACT_VERSION,
                engineVersion,
                reviewCase.caseId(),
                reviewCase.basisRevision(),
                basisFacts,
                checklist.snapshotId(),
                checklist.snapshotDigest(),
                snapshot.materialSetRevision(),
                snapshot.assignmentRevision(),
                snapshot.nameRevision(),
                upload.uploadConfirmationId(),
                upload.materialSetDigest(),
                sourceFacts,
                materialFacts,
                assignmentFacts,
                diagnosticFacts);
    }

    private static MaterialCollectionSnapshot activeSnapshot(MaterialCollectionSnapshot raw) {
        List<SubmissionSource> sources = raw.sources().stream()
                .filter(item -> item.status() == SubmissionSourceStatus.ATTACHED)
                .toList();
        List<SubmissionMaterial> materials = raw.materials().stream()
                .filter(item -> item.status() != MaterialStatus.REMOVED)
                .toList();
        Set<String> sourceIds = sources.stream().map(SubmissionSource::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> materialIds = materials.stream().map(SubmissionMaterial::materialId)
                .collect(java.util.stream.Collectors.toSet());
        List<InputDiagnostic> diagnostics = raw.diagnostics().stream()
                .filter(item -> item.subjectType() != DiagnosticSubjectType.OFFICIAL_BASIS)
                .filter(item -> item.sourceId() != null && sourceIds.contains(item.sourceId())
                        || item.materialId() != null && materialIds.contains(item.materialId()))
                .toList();
        return new MaterialCollectionSnapshot(
                raw.caseId(), raw.materialSetRevision(), raw.assignmentRevision(), raw.nameRevision(),
                sources, materials, raw.assignments(), diagnostics, raw.uploadConfirmation());
    }

    private static UploadConfirmation requireUpload(
            String ownerId,
            String caseId,
            String checklistSnapshotId,
            String uploadConfirmationId,
            MaterialCollectionSnapshot snapshot
    ) {
        UploadConfirmation upload = snapshot.uploadConfirmation();
        if (upload == null
                || !upload.uploadConfirmationId().equals(uploadConfirmationId)
                || !upload.caseId().equals(caseId)
                || !upload.checklistSnapshotId().equals(checklistSnapshotId)
                || upload.materialSetRevision() != snapshot.materialSetRevision()
                || !upload.confirmedBy().equals(ownerId)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED,
                    "A current upload confirmation is required");
        }
        return upload;
    }

    private ReviewCase requireCase(String ownerId, String caseId) {
        return caseStore.findOwned(ownerId, caseId).orElseThrow(() -> new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND,
                "Review case was not found"));
    }

    private static boolean sameInput(
            ReviewCase before,
            ReviewCase after,
            String checklistSnapshotId,
            MaterialCollectionSnapshot snapshot
    ) {
        return before.version() == after.version()
                && before.basisRevision() == after.basisRevision()
                && before.materialSetRevision() == snapshot.materialSetRevision()
                && before.assignmentRevision() == snapshot.assignmentRevision()
                && before.nameRevision() == snapshot.nameRevision()
                && Objects.equals(before.currentChecklistSnapshotId(), checklistSnapshotId)
                && Objects.equals(after.currentChecklistSnapshotId(), checklistSnapshotId);
    }

    private static String subjectId(InputDiagnostic diagnostic) {
        return diagnostic.sourceId() != null ? diagnostic.sourceId() : diagnostic.materialId();
    }

    private static String locatorDigest(InputDiagnostic diagnostic) {
        if (diagnostic.evidenceDigest() != null) {
            return diagnostic.evidenceDigest();
        }
        return diagnostic.locatorJson() == null
                ? null
                : CanonicalJson.sha256(Map.of("locator", diagnostic.locatorJson()));
    }

    private static String requireEngineVersion(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("engineVersion is invalid");
        }
        return value;
    }
}
