package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.model.InputDiagnostic;
import com.javaee.aiservice.review.m0.model.MaterialAssignment;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReviewM0MaterialRepository {
    List<SubmissionSource> listSourcesOwned(String ownerId, String caseId);

    List<SubmissionMaterial> listMaterialsOwned(String ownerId, String caseId);

    List<InputDiagnostic> listDiagnosticsOwned(String ownerId, String caseId);

    List<MaterialAssignment> listAssignmentsOwned(String ownerId, String caseId, long assignmentRevision);

    Optional<SubmissionSource> findSourceOwned(String ownerId, String caseId, String sourceId);

    Optional<SubmissionMaterial> findMaterialOwned(String ownerId, String caseId, String materialId);

    Optional<UploadConfirmation> findCurrentUploadConfirmationOwned(
            String ownerId,
            String caseId,
            String checklistSnapshotId,
            long materialSetRevision
    );

    SubmissionSource insertSourceOwned(String ownerId, SubmissionSource source);

    SubmissionMaterial insertMaterialOwned(String ownerId, SubmissionMaterial material);

    InputDiagnostic insertDiagnosticOwned(String ownerId, InputDiagnostic diagnostic);

    SubmissionSource removeSourceOwned(
            String ownerId,
            String caseId,
            String sourceId,
            long expectedVersion,
            long removedMaterialSetRevision,
            Instant removedAt
    );

    void removeMaterialsForSourceOwned(
            String ownerId,
            String caseId,
            String sourceId,
            long removedMaterialSetRevision,
            Instant removedAt
    );

    SubmissionMaterial renameMaterialOwned(
            String ownerId,
            String caseId,
            String materialId,
            long expectedVersion,
            String submissionFileName,
            String normalizedSubmissionFileName,
            long nameRevision,
            Instant renamedAt
    );

    boolean materialExistsOwned(String ownerId, String caseId, String materialId);

    boolean requirementExistsOwned(String ownerId, String caseId, String requirementId);

    MaterialAssignment insertAssignmentOwned(String ownerId, MaterialAssignment assignment);

    UploadConfirmation insertUploadConfirmationOwned(String ownerId, UploadConfirmation confirmation);
}
