package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record InputDiagnostic(
        String diagnosticId, String caseId, DiagnosticSubjectType subjectType,
        String basisId, String sourceId, String materialId, CannotConfirmReason cannotConfirmReason,
        String failureStage, String locatorJson, String evidenceDigest, Instant createdAt
) {
    public InputDiagnostic {
        ModelChecks.required(diagnosticId, "diagnosticId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(subjectType, "subjectType");
        ModelChecks.required(cannotConfirmReason, "cannotConfirmReason");
        int subjects = (basisId == null ? 0 : 1) + (sourceId == null ? 0 : 1) + (materialId == null ? 0 : 1);
        if (subjects != 1
                || (subjectType == DiagnosticSubjectType.OFFICIAL_BASIS) != (basisId != null)
                || (subjectType == DiagnosticSubjectType.SUBMISSION_SOURCE) != (sourceId != null)
                || (subjectType == DiagnosticSubjectType.MATERIAL) != (materialId != null)) {
            throw ModelChecks.invalid("Diagnostic subject type must match exactly one subject id");
        }
        if (evidenceDigest != null) {
            ModelChecks.digest(evidenceDigest, "evidenceDigest");
        }
        ModelChecks.required(createdAt, "createdAt");
    }
}
