package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record RequirementRevision(
        String requirementRevisionId, String requirementId, String checklistRevisionId, String displayName,
        boolean enabled, RequirementApplicability applicability, int minimumCount, FileNameRule fileNameRule,
        RequirementAuthorship authorship, String sourceRefsJson, long itemVersion, Instant createdAt
) {
    public RequirementRevision {
        ModelChecks.required(requirementRevisionId, "requirementRevisionId", 64);
        ModelChecks.required(requirementId, "requirementId", 64);
        ModelChecks.required(checklistRevisionId, "checklistRevisionId", 64);
        ModelChecks.required(displayName, "displayName", 1024);
        ModelChecks.required(applicability, "applicability");
        ModelChecks.required(fileNameRule, "fileNameRule");
        ModelChecks.required(authorship, "authorship");
        if (minimumCount < 1) {
            throw ModelChecks.invalid("minimumCount must be at least one");
        }
        ModelChecks.required(sourceRefsJson, "sourceRefsJson", Integer.MAX_VALUE);
        ModelChecks.required(createdAt, "createdAt");
    }
}
