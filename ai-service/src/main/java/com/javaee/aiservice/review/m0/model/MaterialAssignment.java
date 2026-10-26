package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record MaterialAssignment(
        String assignmentId, String caseId, String materialId, String requirementId,
        AssignmentStatus status, long assignmentRevision, String decidedBy, Instant createdAt
) {
    public MaterialAssignment {
        ModelChecks.required(assignmentId, "assignmentId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(materialId, "materialId", 64);
        ModelChecks.required(requirementId, "requirementId", 64);
        ModelChecks.required(status, "status");
        ModelChecks.required(createdAt, "createdAt");
    }
}
