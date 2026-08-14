package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record UploadConfirmation(
        String uploadConfirmationId, String caseId, String checklistSnapshotId,
        long materialSetRevision, String materialSetDigest, String confirmedBy, Instant confirmedAt
) {
    public UploadConfirmation {
        ModelChecks.required(uploadConfirmationId, "uploadConfirmationId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(checklistSnapshotId, "checklistSnapshotId", 64);
        ModelChecks.digest(materialSetDigest, "materialSetDigest");
        ModelChecks.required(confirmedBy, "confirmedBy", 128);
        ModelChecks.required(confirmedAt, "confirmedAt");
    }
}
