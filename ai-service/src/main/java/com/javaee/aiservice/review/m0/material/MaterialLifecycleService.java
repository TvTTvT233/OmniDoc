package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.archive.ArchiveEntryArtifact;
import com.javaee.aiservice.review.m0.archive.ArchiveInspection;
import com.javaee.aiservice.review.m0.archive.ArchiveInspectionException;
import com.javaee.aiservice.review.m0.archive.ZipArchiveInspector;
import com.javaee.aiservice.review.m0.file.ContentInspection;
import com.javaee.aiservice.review.m0.file.ContentInspectionException;
import com.javaee.aiservice.review.m0.file.FileArtifactSnapshot;
import com.javaee.aiservice.review.m0.file.FormalContentInspector;
import com.javaee.aiservice.review.m0.file.FormalContentTypes;
import com.javaee.aiservice.review.m0.file.OwnedFileAccessException;
import com.javaee.aiservice.review.m0.file.ReviewM0FileAccess;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.DiagnosticSubjectType;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.InputDiagnostic;
import com.javaee.aiservice.review.m0.model.LogicalFileName;
import com.javaee.aiservice.review.m0.model.MaterialAssignment;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;
import com.javaee.aiservice.review.m0.model.SubmissionSourceStatus;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MaterialLifecycleService {
    private final ReviewM0MaterialRepository repository;
    private final ReviewCaseStore caseStore;
    private final ReviewM0FileAccess fileAccess;
    private final ZipArchiveInspector archiveInspector;
    private final FormalContentInspector contentInspector;
    private final TransactionTemplate transactions;

    public MaterialLifecycleService(
            ReviewM0MaterialRepository repository,
            ReviewCaseStore caseStore,
            ReviewM0FileAccess fileAccess,
            ZipArchiveInspector archiveInspector,
            FormalContentInspector contentInspector,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.caseStore = Objects.requireNonNull(caseStore);
        this.fileAccess = Objects.requireNonNull(fileAccess);
        this.archiveInspector = Objects.requireNonNull(archiveInspector);
        this.contentInspector = Objects.requireNonNull(contentInspector);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public MaterialAttachmentResult attachOwned(
            String ownerId,
            String caseId,
            String fileId,
            String submissionFileName
    ) {
        requireOwnedCase(ownerId, caseId);
        PreparedAttachment prepared = prepareAttachment(ownerId, fileId, submissionFileName);
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase current = requireOwnedCase(ownerId, caseId);
            long newRevision = current.materialSetRevision() + 1;
            Instant now = Instant.now();
            String sourceId = newId("source");
            SubmissionSource source = new SubmissionSource(
                    sourceId, caseId, fileId, prepared.snapshot.originalFileName(),
                    prepared.snapshot.declaredContentType(), prepared.type, SubmissionSourceStatus.ATTACHED,
                    prepared.snapshot.sizeBytes(), prepared.snapshot.contentDigest(), prepared.snapshot.storageVersion(),
                    newRevision, null, 0, now, now);
            repository.insertSourceOwned(ownerId, source);

            List<SubmissionMaterial> materials = new ArrayList<>();
            List<InputDiagnostic> diagnostics = new ArrayList<>();
            for (PreparedMaterial item : prepared.materials) {
                String materialId = newId("material");
                LogicalFileName logicalName = new LogicalFileName(item.submissionFileName);
                logicalName.requireType(item.type);
                SubmissionMaterial material = new SubmissionMaterial(
                        materialId, caseId, sourceId, item.kind, item.normalizedEntryPath,
                        item.originalEntryName, item.entryIndex, item.contentDigest, item.type,
                        logicalName.original(), logicalName.normalized(), item.status,
                        item.reason, item.failureStage, newRevision, current.nameRevision(),
                        0, now, now);
                repository.insertMaterialOwned(ownerId, material);
                materials.add(material);
                if (item.reason != null) {
                    InputDiagnostic diagnostic = diagnostic(
                            caseId, DiagnosticSubjectType.MATERIAL, null, materialId,
                            item.reason, item.failureStage, null, now);
                    repository.insertDiagnosticOwned(ownerId, diagnostic);
                    diagnostics.add(diagnostic);
                }
            }
            for (PreparedDiagnostic item : prepared.sourceDiagnostics) {
                InputDiagnostic diagnostic = diagnostic(
                        caseId, DiagnosticSubjectType.SUBMISSION_SOURCE, sourceId, null,
                        item.reason, item.failureStage, item.locator, now);
                repository.insertDiagnosticOwned(ownerId, diagnostic);
                diagnostics.add(diagnostic);
            }
            ReviewCase advanced = caseStore.advanceRevisionOwned(
                    ownerId, caseId, current.version(), RevisionKind.MATERIAL_SET, "MATERIAL_SET_CHANGED");
            return new MaterialAttachmentResult(source, materials, diagnostics, advanced.materialSetRevision());
        }));
    }

    public SubmissionSource removeSourceOwned(
            String ownerId,
            String caseId,
            String sourceId,
            long expectedSourceVersion
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase current = requireOwnedCase(ownerId, caseId);
            SubmissionSource existing = repository.findSourceOwned(ownerId, caseId, sourceId)
                    .orElseThrow(MaterialErrors::resourceNotFound);
            if (existing.status() == SubmissionSourceStatus.REMOVED) {
                return existing;
            }
            long newRevision = current.materialSetRevision() + 1;
            Instant now = Instant.now();
            SubmissionSource removed = repository.removeSourceOwned(
                    ownerId, caseId, sourceId, expectedSourceVersion, newRevision, now);
            repository.removeMaterialsForSourceOwned(ownerId, caseId, sourceId, newRevision, now);
            caseStore.advanceRevisionOwned(
                    ownerId, caseId, current.version(), RevisionKind.MATERIAL_SET, "MATERIAL_SOURCE_REMOVED");
            return removed;
        }));
    }

    public SubmissionMaterial renameOwned(
            String ownerId,
            String caseId,
            String materialId,
            long expectedMaterialVersion,
            String submissionFileName
    ) {
        LogicalFileName logicalName = new LogicalFileName(submissionFileName);
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase current = requireOwnedCase(ownerId, caseId);
            SubmissionMaterial material = repository.findMaterialOwned(ownerId, caseId, materialId)
                    .orElseThrow(MaterialErrors::resourceNotFound);
            if (material.status() == MaterialStatus.REMOVED) {
                throw MaterialErrors.resourceNotFound();
            }
            logicalName.requireType(material.formalFileType());
            if (material.submissionFileName().equals(logicalName.original())
                    && material.normalizedSubmissionFileName().equals(logicalName.normalized())) {
                if (material.version() != expectedMaterialVersion) {
                    throw MaterialErrors.versionConflict("Submission material changed concurrently");
                }
                return material;
            }
            long newRevision = current.nameRevision() + 1;
            SubmissionMaterial renamed = repository.renameMaterialOwned(
                    ownerId, caseId, materialId, expectedMaterialVersion,
                    logicalName.original(), logicalName.normalized(), newRevision, Instant.now());
            caseStore.advanceRevisionOwned(
                    ownerId, caseId, current.version(), RevisionKind.NAME, "SUBMISSION_FILE_NAME_CHANGED");
            return renamed;
        }));
    }

    public List<MaterialAssignment> replaceAssignmentsOwned(
            String ownerId,
            String caseId,
            long expectedAssignmentRevision,
            List<AssignmentChange> changes
    ) {
        List<AssignmentChange> requested = changes == null ? List.of() : List.copyOf(changes);
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase current = requireOwnedCase(ownerId, caseId);
            if (current.assignmentRevision() != expectedAssignmentRevision) {
                throw MaterialErrors.versionConflict("Assignment revision changed concurrently");
            }
            Map<AssignmentKey, AssignmentStatus> requestedMap = new LinkedHashMap<>();
            for (AssignmentChange change : requested) {
                AssignmentKey key = new AssignmentKey(change.materialId(), change.requirementId());
                if (requestedMap.putIfAbsent(key, change.status()) != null) {
                    throw MaterialErrors.invalid("Assignment request contains duplicate relationships");
                }
                if (!repository.materialExistsOwned(ownerId, caseId, change.materialId())
                        || !repository.requirementExistsOwned(ownerId, caseId, change.requirementId())) {
                    throw MaterialErrors.resourceNotFound();
                }
            }
            List<MaterialAssignment> previous = repository.listAssignmentsOwned(
                    ownerId, caseId, current.assignmentRevision());
            Map<AssignmentKey, AssignmentStatus> previousMap = new LinkedHashMap<>();
            for (MaterialAssignment assignment : previous) {
                previousMap.put(new AssignmentKey(assignment.materialId(), assignment.requirementId()),
                        assignment.status());
            }
            if (requestedMap.equals(previousMap)) {
                return previous;
            }
            Set<AssignmentKey> union = new HashSet<>(previousMap.keySet());
            union.addAll(requestedMap.keySet());
            List<AssignmentKey> ordered = union.stream()
                    .sorted(Comparator.comparing(AssignmentKey::materialId)
                            .thenComparing(AssignmentKey::requirementId))
                    .toList();
            long newRevision = current.assignmentRevision() + 1;
            Instant now = Instant.now();
            List<MaterialAssignment> result = new ArrayList<>();
            for (AssignmentKey key : ordered) {
                AssignmentStatus assignmentStatus = requestedMap.getOrDefault(key, AssignmentStatus.REJECTED);
                MaterialAssignment assignment = new MaterialAssignment(
                        newId("assignment"), caseId, key.materialId(), key.requirementId(),
                        assignmentStatus, newRevision, ownerId, now);
                repository.insertAssignmentOwned(ownerId, assignment);
                result.add(assignment);
            }
            caseStore.advanceRevisionOwned(
                    ownerId, caseId, current.version(), RevisionKind.ASSIGNMENT, "MATERIAL_ASSIGNMENT_CHANGED");
            return List.copyOf(result);
        }));
    }

    public UploadConfirmation confirmUploadOwned(
            String ownerId,
            String caseId,
            String checklistSnapshotId,
            long materialSetRevision,
            boolean acknowledgedUploadComplete
    ) {
        if (!acknowledgedUploadComplete) {
            throw MaterialErrors.invalid("acknowledgedUploadComplete must be true");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase current = requireOwnedCase(ownerId, caseId);
            if (current.currentChecklistSnapshotId() == null
                    || !current.currentChecklistSnapshotId().equals(checklistSnapshotId)) {
                throw MaterialErrors.checklistNotConfirmed();
            }
            ChecklistSnapshot snapshot = caseStore.findChecklistSnapshotOwned(
                            ownerId, caseId, checklistSnapshotId)
                    .filter(item -> item.lifecycle() == ChecklistLifecycle.CONFIRMED)
                    .orElseThrow(MaterialErrors::checklistNotConfirmed);
            if (!snapshot.checklistSnapshotId().equals(checklistSnapshotId)) {
                throw MaterialErrors.checklistNotConfirmed();
            }
            if (current.materialSetRevision() != materialSetRevision) {
                throw MaterialErrors.versionConflict("Material set revision changed concurrently");
            }
            List<SubmissionSource> sources = activeSources(repository.listSourcesOwned(ownerId, caseId));
            List<SubmissionMaterial> materials = activeMaterials(repository.listMaterialsOwned(ownerId, caseId));
            List<InputDiagnostic> diagnostics = currentDiagnostics(
                    repository.listDiagnosticsOwned(ownerId, caseId), sources, materials);
            String digest = materialSetDigest(sources, materials, diagnostics);
            Optional<UploadConfirmation> existing = repository.findCurrentUploadConfirmationOwned(
                    ownerId, caseId, checklistSnapshotId, materialSetRevision);
            if (existing.isPresent() && existing.get().materialSetDigest().equals(digest)) {
                return existing.get();
            }
            UploadConfirmation confirmation = new UploadConfirmation(
                    newId("upload"), caseId, checklistSnapshotId, materialSetRevision,
                    digest, ownerId, Instant.now());
            return repository.insertUploadConfirmationOwned(ownerId, confirmation);
        }));
    }

    public MaterialCollectionSnapshot getSnapshotOwned(String ownerId, String caseId) {
        ReviewCase reviewCase = requireOwnedCase(ownerId, caseId);
        List<SubmissionSource> sources = repository.listSourcesOwned(ownerId, caseId);
        List<SubmissionMaterial> materials = repository.listMaterialsOwned(ownerId, caseId);
        List<InputDiagnostic> diagnostics = repository.listDiagnosticsOwned(ownerId, caseId);
        List<MaterialAssignment> assignments = repository.listAssignmentsOwned(
                ownerId, caseId, reviewCase.assignmentRevision());
        UploadConfirmation confirmation = reviewCase.currentChecklistSnapshotId() == null
                ? null
                : repository.findCurrentUploadConfirmationOwned(
                        ownerId, caseId, reviewCase.currentChecklistSnapshotId(),
                        reviewCase.materialSetRevision()).orElse(null);
        return new MaterialCollectionSnapshot(
                caseId, reviewCase.materialSetRevision(), reviewCase.assignmentRevision(),
                reviewCase.nameRevision(), sources, materials, assignments, diagnostics, confirmation);
    }

    public MaterialCollectionSnapshot requireReviewReadyOwned(String ownerId, String caseId) {
        ReviewCase reviewCase = requireOwnedCase(ownerId, caseId);
        if (reviewCase.currentChecklistSnapshotId() == null) {
            throw MaterialErrors.checklistNotConfirmed();
        }
        UploadConfirmation confirmation = repository.findCurrentUploadConfirmationOwned(
                        ownerId, caseId, reviewCase.currentChecklistSnapshotId(),
                        reviewCase.materialSetRevision())
                .orElseThrow(MaterialErrors::uploadNotConfirmed);

        List<SubmissionSource> sources = activeSources(repository.listSourcesOwned(ownerId, caseId));
        List<SubmissionMaterial> materials = activeMaterials(repository.listMaterialsOwned(ownerId, caseId));
        List<InputDiagnostic> diagnostics = new ArrayList<>(currentDiagnostics(
                repository.listDiagnosticsOwned(ownerId, caseId), sources, materials));
        List<InputDiagnostic> newlyDetectedSourceChanges = new ArrayList<>();
        for (SubmissionSource source : sources) {
            verifySourceForReview(ownerId, source, materials).ifPresent(diagnostic -> {
                if (!hasEquivalentDiagnostic(source, diagnostic, materials, diagnostics)) {
                    newlyDetectedSourceChanges.add(diagnostic);
                }
            });
        }
        if (!newlyDetectedSourceChanges.isEmpty()) {
            persistDetectedSourceChanges(
                    ownerId,
                    caseId,
                    reviewCase.version(),
                    reviewCase.materialSetRevision(),
                    newlyDetectedSourceChanges);
            throw MaterialErrors.uploadNotConfirmed();
        }
        List<MaterialAssignment> assignments = repository.listAssignmentsOwned(
                ownerId, caseId, reviewCase.assignmentRevision());
        return new MaterialCollectionSnapshot(
                caseId, reviewCase.materialSetRevision(), reviewCase.assignmentRevision(),
                reviewCase.nameRevision(), sources, materials, assignments, diagnostics, confirmation);
    }

    private void persistDetectedSourceChanges(
            String ownerId,
            String caseId,
            long expectedCaseVersion,
            long expectedMaterialSetRevision,
            List<InputDiagnostic> diagnostics
    ) {
        transactions.executeWithoutResult(status -> {
            ReviewCase current = requireOwnedCase(ownerId, caseId);
            if (current.version() != expectedCaseVersion
                    || current.materialSetRevision() != expectedMaterialSetRevision) {
                throw MaterialErrors.versionConflict("Material set changed while source facts were verified");
            }
            for (InputDiagnostic diagnostic : diagnostics) {
                repository.insertDiagnosticOwned(ownerId, diagnostic);
            }
            caseStore.advanceRevisionOwned(
                    ownerId,
                    caseId,
                    current.version(),
                    RevisionKind.MATERIAL_SET,
                    "MATERIAL_SOURCE_FACTS_CHANGED");
        });
    }

    private static boolean hasEquivalentDiagnostic(
            SubmissionSource source,
            InputDiagnostic candidate,
            List<SubmissionMaterial> materials,
            List<InputDiagnostic> existing
    ) {
        Set<String> sourceMaterialIds = materials.stream()
                .filter(material -> material.sourceId().equals(source.sourceId()))
                .map(SubmissionMaterial::materialId)
                .collect(java.util.stream.Collectors.toSet());
        return existing.stream().anyMatch(diagnostic ->
                diagnostic.cannotConfirmReason() == candidate.cannotConfirmReason()
                        && (source.sourceId().equals(diagnostic.sourceId())
                        || diagnostic.materialId() != null
                        && sourceMaterialIds.contains(diagnostic.materialId())));
    }

    private PreparedAttachment prepareAttachment(
            String ownerId,
            String fileId,
            String submissionFileName
    ) {
        FileArtifactSnapshot snapshot = fileAccess.getOwnedFile(ownerId, fileId);
        FormalFileType type = FormalFileType.fromFileName(snapshot.originalFileName())
                .orElseThrow(MaterialErrors::unsupported);
        if (type == FormalFileType.ZIP && submissionFileName != null) {
            throw MaterialErrors.invalid("submissionFileName must be null for a ZIP source");
        }
        if (type != FormalFileType.ZIP && submissionFileName == null) {
            throw MaterialErrors.invalid("submissionFileName is required for an ordinary source");
        }
        if (type != FormalFileType.ZIP) {
            LogicalFileName logicalName = new LogicalFileName(submissionFileName);
            logicalName.requireType(type);
        }

        if (!FormalContentTypes.isCompatible(snapshot.declaredContentType(), type)) {
            return preparedTypeMismatch(snapshot, type, submissionFileName);
        }
        if (!snapshot.available()) {
            return preparedUnavailable(snapshot, type, submissionFileName, "SOURCE_AVAILABILITY");
        }

        PreparedAttachment prepared;
        if (type == FormalFileType.ZIP) {
            prepared = inspectZip(ownerId, snapshot);
        } else {
            prepared = inspectOrdinary(ownerId, snapshot, type, submissionFileName);
        }
        FileArtifactSnapshot after = fileAccess.getOwnedFile(ownerId, fileId);
        if (!snapshot.sameImmutableFacts(after)) {
            throw MaterialErrors.fileUnavailable(new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.INTEGRITY_MISMATCH,
                    "Owned file facts changed while preparing the material snapshot"));
        }
        return prepared;
    }

    private PreparedAttachment inspectZip(String ownerId, FileArtifactSnapshot snapshot) {
        try (InputStream input = fileAccess.openFile(ownerId, snapshot.fileId())) {
            ArchiveInspection inspection = archiveInspector.inspect(input);
            if (inspection.archiveSizeBytes() != snapshot.sizeBytes()
                    || !inspection.archiveContentDigest().equals(snapshot.contentDigest())) {
                return preparedUnavailable(snapshot, FormalFileType.ZIP, null, "ARCHIVE_SOURCE_DIGEST");
            }
            List<PreparedMaterial> materials = inspection.entries().stream()
                    .map(entry -> new PreparedMaterial(
                            MaterialKind.ZIP_ENTRY, entry.normalizedEntryPath(), entry.originalEntryName(),
                            entry.entryIndex(), entry.contentDigest(), entry.formalFileType(), entry.leafFileName(),
                            MaterialStatus.AVAILABLE, null, null))
                    .toList();
            return new PreparedAttachment(snapshot, FormalFileType.ZIP, materials, List.of());
        } catch (ArchiveInspectionException error) {
            CannotConfirmReason reason = causedByOwnedIntegrity(error)
                    ? CannotConfirmReason.SOURCE_FILE_UNAVAILABLE
                    : error.reason();
            String locator = diagnosticLocator(error.entryIndex(), error.entryNameDigest());
            return new PreparedAttachment(snapshot, FormalFileType.ZIP, List.of(),
                    List.of(new PreparedDiagnostic(reason, error.failureStage(), locator)));
        } catch (OwnedFileAccessException error) {
            if (isDeepSourceFailure(error)) {
                return preparedUnavailable(snapshot, FormalFileType.ZIP, null, "ARCHIVE_SOURCE_READ");
            }
            throw error;
        } catch (IOException error) {
            if (causedByOwnedIntegrity(error)) {
                return preparedUnavailable(snapshot, FormalFileType.ZIP, null, "ARCHIVE_SOURCE_READ");
            }
            throw MaterialErrors.fileUnavailable(error);
        }
    }

    private PreparedAttachment inspectOrdinary(
            String ownerId,
            FileArtifactSnapshot snapshot,
            FormalFileType type,
            String submissionFileName
    ) {
        try (InputStream input = fileAccess.openFile(ownerId, snapshot.fileId())) {
            ContentInspection inspection = contentInspector.inspect(type, input);
            if (inspection.sizeBytes() != snapshot.sizeBytes()
                    || !inspection.contentDigest().equals(snapshot.contentDigest())) {
                return preparedUnavailable(snapshot, type, submissionFileName, "SOURCE_DIGEST");
            }
            return new PreparedAttachment(snapshot, type,
                    List.of(new PreparedMaterial(
                            MaterialKind.TOP_LEVEL_FILE, null, null, null,
                            inspection.contentDigest(), type, submissionFileName,
                            MaterialStatus.AVAILABLE, null, null)),
                    List.of());
        } catch (ContentInspectionException error) {
            return preparedCannotConfirm(snapshot, type, submissionFileName,
                    error.reason(), error.failureStage());
        } catch (OwnedFileAccessException error) {
            if (isDeepSourceFailure(error)) {
                return preparedUnavailable(snapshot, type, submissionFileName, "SOURCE_READ");
            }
            throw error;
        } catch (IOException error) {
            if (causedByOwnedIntegrity(error)) {
                return preparedUnavailable(snapshot, type, submissionFileName, "SOURCE_READ");
            }
            throw MaterialErrors.fileUnavailable(error);
        }
    }

    private PreparedAttachment preparedTypeMismatch(
            FileArtifactSnapshot snapshot,
            FormalFileType type,
            String submissionFileName
    ) {
        if (type == FormalFileType.ZIP) {
            return new PreparedAttachment(snapshot, type, List.of(),
                    List.of(new PreparedDiagnostic(
                            CannotConfirmReason.TYPE_MISMATCH, "DECLARED_CONTENT_TYPE", null)));
        }
        return preparedCannotConfirm(snapshot, type, submissionFileName,
                CannotConfirmReason.TYPE_MISMATCH, "DECLARED_CONTENT_TYPE");
    }

    private PreparedAttachment preparedUnavailable(
            FileArtifactSnapshot snapshot,
            FormalFileType type,
            String submissionFileName,
            String failureStage
    ) {
        if (type == FormalFileType.ZIP) {
            return new PreparedAttachment(snapshot, type, List.of(),
                    List.of(new PreparedDiagnostic(
                            CannotConfirmReason.SOURCE_FILE_UNAVAILABLE, failureStage, null)));
        }
        return preparedCannotConfirm(snapshot, type, submissionFileName,
                CannotConfirmReason.SOURCE_FILE_UNAVAILABLE, failureStage);
    }

    private PreparedAttachment preparedCannotConfirm(
            FileArtifactSnapshot snapshot,
            FormalFileType type,
            String submissionFileName,
            CannotConfirmReason reason,
            String failureStage
    ) {
        return new PreparedAttachment(snapshot, type,
                List.of(new PreparedMaterial(
                        MaterialKind.TOP_LEVEL_FILE, null, null, null,
                        snapshot.contentDigest(), type, submissionFileName,
                        MaterialStatus.CANNOT_CONFIRM, reason, failureStage)),
                List.of());
    }

    private Optional<InputDiagnostic> verifySourceForReview(
            String ownerId,
            SubmissionSource source,
            List<SubmissionMaterial> allMaterials
    ) {
        try {
            FileArtifactSnapshot current = fileAccess.getOwnedFile(ownerId, source.fileId());
            if (!current.available() || !sourceMatches(source, current)) {
                return Optional.of(ephemeralSourceDiagnostic(source, CannotConfirmReason.SOURCE_FILE_UNAVAILABLE,
                        "REVIEW_SOURCE_SNAPSHOT"));
            }
            if (!FormalContentTypes.isCompatible(current.declaredContentType(), source.formalFileType())) {
                return Optional.of(ephemeralSourceDiagnostic(
                        source, CannotConfirmReason.TYPE_MISMATCH, "REVIEW_DECLARED_CONTENT_TYPE"));
            }
            if (source.formalFileType() == FormalFileType.ZIP) {
                try (InputStream input = fileAccess.openFile(ownerId, source.fileId())) {
                    ArchiveInspection inspection = archiveInspector.inspect(input);
                    List<SubmissionMaterial> expected = allMaterials.stream()
                            .filter(material -> material.sourceId().equals(source.sourceId()))
                            .toList();
                    if (!archiveIdentityMatches(inspection.entries(), expected)
                            || inspection.archiveSizeBytes() != source.fileSizeBytes()
                            || !inspection.archiveContentDigest().equals(source.fileDigest())) {
                        return Optional.of(ephemeralSourceDiagnostic(
                                source, CannotConfirmReason.SOURCE_FILE_UNAVAILABLE, "REVIEW_ARCHIVE_IDENTITY"));
                    }
                }
            } else {
                try (InputStream input = fileAccess.openFile(ownerId, source.fileId())) {
                    ContentInspection inspection = contentInspector.inspect(source.formalFileType(), input);
                    if (inspection.sizeBytes() != source.fileSizeBytes()
                            || !inspection.contentDigest().equals(source.fileDigest())) {
                        return Optional.of(ephemeralSourceDiagnostic(
                                source, CannotConfirmReason.SOURCE_FILE_UNAVAILABLE, "REVIEW_SOURCE_DIGEST"));
                    }
                }
            }
            return Optional.empty();
        } catch (ArchiveInspectionException error) {
            CannotConfirmReason reason = causedByOwnedIntegrity(error)
                    ? CannotConfirmReason.SOURCE_FILE_UNAVAILABLE
                    : error.reason();
            return Optional.of(ephemeralSourceDiagnostic(source, reason, error.failureStage()));
        } catch (ContentInspectionException error) {
            return Optional.of(ephemeralSourceDiagnostic(source, error.reason(), error.failureStage()));
        } catch (OwnedFileAccessException error) {
            if (error.kind() == OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE) {
                throw error;
            }
            return Optional.of(ephemeralSourceDiagnostic(
                    source, CannotConfirmReason.SOURCE_FILE_UNAVAILABLE, "REVIEW_SOURCE_READ"));
        } catch (IOException error) {
            if (causedByOwnedIntegrity(error)) {
                return Optional.of(ephemeralSourceDiagnostic(
                        source, CannotConfirmReason.SOURCE_FILE_UNAVAILABLE, "REVIEW_SOURCE_READ"));
            }
            throw MaterialErrors.fileUnavailable(error);
        }
    }

    private static boolean archiveIdentityMatches(
            List<ArchiveEntryArtifact> actual,
            List<SubmissionMaterial> expected
    ) {
        if (actual.size() != expected.size()) {
            return false;
        }
        Map<String, SubmissionMaterial> byPath = new HashMap<>();
        for (SubmissionMaterial material : expected) {
            byPath.put(material.normalizedEntryPath(), material);
        }
        for (ArchiveEntryArtifact entry : actual) {
            SubmissionMaterial material = byPath.get(entry.normalizedEntryPath());
            if (material == null
                    || material.entryIndex() == null
                    || material.entryIndex() != entry.entryIndex()
                    || material.formalFileType() != entry.formalFileType()
                    || !material.contentDigest().equals(entry.contentDigest())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sourceMatches(SubmissionSource source, FileArtifactSnapshot current) {
        return source.fileId().equals(current.fileId())
                && source.originalFileNameSnapshot().equals(current.originalFileName())
                && Objects.equals(source.declaredContentType(), current.declaredContentType())
                && source.fileSizeBytes() == current.sizeBytes()
                && source.fileDigest().equals(current.contentDigest())
                && Objects.equals(source.storageVersion(), current.storageVersion());
    }

    private static InputDiagnostic ephemeralSourceDiagnostic(
            SubmissionSource source,
            CannotConfirmReason reason,
            String stage
    ) {
        return new InputDiagnostic(
                "diagnostic-runtime-" + UUID.randomUUID(), source.caseId(),
                DiagnosticSubjectType.SUBMISSION_SOURCE, null, source.sourceId(), null,
                reason, stage, null, source.fileDigest(), Instant.now());
    }

    private static InputDiagnostic diagnostic(
            String caseId,
            DiagnosticSubjectType subjectType,
            String sourceId,
            String materialId,
            CannotConfirmReason reason,
            String failureStage,
            String locator,
            Instant now
    ) {
        String evidenceDigest = locator == null ? null : CanonicalJson.sha256(Map.of("locator", locator));
        return new InputDiagnostic(
                newId("diagnostic"), caseId, subjectType, null, sourceId, materialId,
                reason, failureStage, locator, evidenceDigest, now);
    }

    private static String diagnosticLocator(Integer entryIndex, String entryNameDigest) {
        if (entryIndex == null && entryNameDigest == null) {
            return null;
        }
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("entryIndex", entryIndex);
        locator.put("entryNameDigest", entryNameDigest);
        return CanonicalJson.write(locator);
    }

    private static String materialSetDigest(
            List<SubmissionSource> sources,
            List<SubmissionMaterial> materials,
            List<InputDiagnostic> diagnostics
    ) {
        List<Map<String, Object>> sourceFacts = sources.stream()
                .sorted(Comparator.comparing(SubmissionSource::sourceId))
                .map(source -> Map.<String, Object>of(
                        "sourceId", source.sourceId(),
                        "fileId", source.fileId(),
                        "fileDigest", source.fileDigest(),
                        "fileSizeBytes", source.fileSizeBytes(),
                        "formalFileType", source.formalFileType()))
                .toList();
        List<Map<String, Object>> materialFacts = materials.stream()
                .sorted(Comparator.comparing(SubmissionMaterial::materialId))
                .map(material -> {
                    Map<String, Object> fact = new LinkedHashMap<>();
                    fact.put("materialId", material.materialId());
                    fact.put("sourceId", material.sourceId());
                    fact.put("kind", material.kind());
                    fact.put("entryPath", material.normalizedEntryPath());
                    fact.put("entryIndex", material.entryIndex());
                    fact.put("contentDigest", material.contentDigest());
                    fact.put("formalFileType", material.formalFileType());
                    fact.put("status", material.status());
                    fact.put("cannotConfirmReason", material.cannotConfirmReason());
                    return fact;
                }).toList();
        List<Map<String, Object>> diagnosticFacts = diagnostics.stream()
                .sorted(Comparator.comparing(InputDiagnostic::diagnosticId))
                .map(diagnostic -> {
                    Map<String, Object> fact = new LinkedHashMap<>();
                    fact.put("subjectType", diagnostic.subjectType());
                    fact.put("subjectId", diagnostic.basisId() != null
                            ? diagnostic.basisId()
                            : diagnostic.sourceId() != null
                                    ? diagnostic.sourceId()
                                    : diagnostic.materialId());
                    fact.put("reason", diagnostic.cannotConfirmReason());
                    fact.put("failureStage", diagnostic.failureStage());
                    return fact;
                }).toList();
        return CanonicalJson.sha256(Map.of(
                "sources", sourceFacts,
                "materials", materialFacts,
                "diagnostics", diagnosticFacts));
    }

    private static List<SubmissionSource> activeSources(List<SubmissionSource> sources) {
        return sources.stream().filter(source -> source.status() == SubmissionSourceStatus.ATTACHED).toList();
    }

    private static List<SubmissionMaterial> activeMaterials(List<SubmissionMaterial> materials) {
        return materials.stream().filter(material -> material.status() != MaterialStatus.REMOVED).toList();
    }

    private static List<InputDiagnostic> currentDiagnostics(
            List<InputDiagnostic> diagnostics,
            List<SubmissionSource> sources,
            List<SubmissionMaterial> materials
    ) {
        Set<String> sourceIds = sources.stream().map(SubmissionSource::sourceId).collect(java.util.stream.Collectors.toSet());
        Set<String> materialIds = materials.stream().map(SubmissionMaterial::materialId).collect(java.util.stream.Collectors.toSet());
        return diagnostics.stream().filter(diagnostic ->
                diagnostic.subjectType() == DiagnosticSubjectType.OFFICIAL_BASIS
                        || diagnostic.sourceId() != null && sourceIds.contains(diagnostic.sourceId())
                        || diagnostic.materialId() != null && materialIds.contains(diagnostic.materialId()))
                .toList();
    }

    private ReviewCase requireOwnedCase(String ownerId, String caseId) {
        return caseStore.findOwned(ownerId, caseId).orElseThrow(MaterialErrors::caseNotFound);
    }

    private static boolean isDeepSourceFailure(OwnedFileAccessException error) {
        return error.kind() == OwnedFileAccessException.Kind.SOURCE_UNAVAILABLE
                || error.kind() == OwnedFileAccessException.Kind.INTEGRITY_MISMATCH;
    }

    private static boolean causedByOwnedIntegrity(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof OwnedFileAccessException owned
                    && isDeepSourceFailure(owned)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record PreparedAttachment(
            FileArtifactSnapshot snapshot,
            FormalFileType type,
            List<PreparedMaterial> materials,
            List<PreparedDiagnostic> sourceDiagnostics
    ) {
    }

    private record PreparedMaterial(
            MaterialKind kind,
            String normalizedEntryPath,
            String originalEntryName,
            Integer entryIndex,
            String contentDigest,
            FormalFileType type,
            String submissionFileName,
            MaterialStatus status,
            CannotConfirmReason reason,
            String failureStage
    ) {
    }

    private record PreparedDiagnostic(
            CannotConfirmReason reason,
            String failureStage,
            String locator
    ) {
    }

    private record AssignmentKey(String materialId, String requirementId) {
    }
}
