package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record ReviewCase(
        String caseId,
        String userId,
        String title,
        ProcessingMode processingMode,
        long basisRevision,
        long checklistRevision,
        long materialSetRevision,
        long assignmentRevision,
        long nameRevision,
        long version,
        String currentChecklistSnapshotId,
        String currentReportId,
        Instant createdAt,
        Instant updatedAt
) {
    public ReviewCase {
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(userId, "userId", 128);
        ModelChecks.required(title, "title", 255);
        ModelChecks.required(processingMode, "processingMode");
        ModelChecks.nonNegative(basisRevision, "basisRevision");
        ModelChecks.nonNegative(checklistRevision, "checklistRevision");
        ModelChecks.nonNegative(materialSetRevision, "materialSetRevision");
        ModelChecks.nonNegative(assignmentRevision, "assignmentRevision");
        ModelChecks.nonNegative(nameRevision, "nameRevision");
        ModelChecks.nonNegative(version, "version");
        ModelChecks.required(createdAt, "createdAt");
        ModelChecks.required(updatedAt, "updatedAt");
    }
}
