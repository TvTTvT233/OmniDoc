package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record ChecklistRequirement(
        String requirementId, String caseId, RequirementOrigin origin, Instant createdAt
) {
    public ChecklistRequirement {
        ModelChecks.required(requirementId, "requirementId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(origin, "origin");
        ModelChecks.required(createdAt, "createdAt");
    }
}
