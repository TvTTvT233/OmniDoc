package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record ChecklistRevision(
        String checklistRevisionId, String caseId, ChecklistLifecycle lifecycle, long basisRevision,
        long revisionNumber, String draftDigest, String extractionJobId, String createdBy, Instant createdAt
) {
    public ChecklistRevision {
        ModelChecks.required(checklistRevisionId, "checklistRevisionId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(lifecycle, "lifecycle");
        ModelChecks.digest(draftDigest, "draftDigest");
        ModelChecks.required(createdBy, "createdBy", 128);
        ModelChecks.required(createdAt, "createdAt");
    }
}
