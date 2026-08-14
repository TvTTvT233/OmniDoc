package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record SubmissionMaterial(
        String materialId, String caseId, String sourceId, MaterialKind kind, String normalizedEntryPath,
        String originalEntryName, Integer entryIndex, String contentDigest, FormalFileType formalFileType,
        String submissionFileName, String normalizedSubmissionFileName, MaterialStatus status,
        CannotConfirmReason cannotConfirmReason, String failureStage, long materialSetRevision,
        long nameRevision, long version, Instant createdAt, Instant updatedAt
) {
    public SubmissionMaterial {
        ModelChecks.required(materialId, "materialId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(sourceId, "sourceId", 64);
        ModelChecks.required(kind, "kind");
        ModelChecks.digest(contentDigest, "contentDigest");
        ModelChecks.required(formalFileType, "formalFileType");
        ModelChecks.required(submissionFileName, "submissionFileName", 1024);
        ModelChecks.required(normalizedSubmissionFileName, "normalizedSubmissionFileName", 1024);
        ModelChecks.required(status, "status");
        if (!formalFileType.isLeafType()) {
            throw ModelChecks.invalid("ZIP containers cannot be persisted as material units");
        }
        if (kind == MaterialKind.TOP_LEVEL_FILE && normalizedEntryPath != null) {
            throw ModelChecks.invalid("Top-level materials cannot have an entry path");
        }
        if (kind == MaterialKind.ZIP_ENTRY && normalizedEntryPath == null) {
            throw ModelChecks.invalid("ZIP entries require a path");
        }
        if ((status == MaterialStatus.CANNOT_CONFIRM) != (cannotConfirmReason != null)) {
            throw ModelChecks.invalid("Material CANNOT_CONFIRM status and reason must agree");
        }
        ModelChecks.required(createdAt, "createdAt");
        ModelChecks.required(updatedAt, "updatedAt");
    }
}
