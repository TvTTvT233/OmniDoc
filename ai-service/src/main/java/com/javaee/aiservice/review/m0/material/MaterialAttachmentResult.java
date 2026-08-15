package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.model.InputDiagnostic;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;

import java.util.List;

public record MaterialAttachmentResult(
        SubmissionSource source,
        List<SubmissionMaterial> materials,
        List<InputDiagnostic> diagnostics,
        long materialSetRevision
) {
    public MaterialAttachmentResult {
        materials = List.copyOf(materials);
        diagnostics = List.copyOf(diagnostics);
    }
}
