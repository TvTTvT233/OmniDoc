package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.model.InputDiagnostic;
import com.javaee.aiservice.review.m0.model.MaterialAssignment;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;

import java.util.List;

public record MaterialCollectionSnapshot(
        String caseId,
        long materialSetRevision,
        long assignmentRevision,
        long nameRevision,
        List<SubmissionSource> sources,
        List<SubmissionMaterial> materials,
        List<MaterialAssignment> assignments,
        List<InputDiagnostic> diagnostics,
        UploadConfirmation uploadConfirmation
) {
    public MaterialCollectionSnapshot {
        sources = List.copyOf(sources);
        materials = List.copyOf(materials);
        assignments = List.copyOf(assignments);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasCannotConfirm() {
        return !diagnostics.isEmpty()
                || materials.stream().anyMatch(material -> material.cannotConfirmReason() != null);
    }
}
