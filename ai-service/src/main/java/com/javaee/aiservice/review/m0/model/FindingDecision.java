package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record FindingDecision(
        String decisionId, String reportId, String findingId, FindingDecisionType decisionType,
        String reason, String decidedBy, Instant decidedAt
) {
    public FindingDecision {
        ModelChecks.required(decisionId, "decisionId", 64);
        ModelChecks.required(reportId, "reportId", 64);
        ModelChecks.required(findingId, "findingId", 64);
        ModelChecks.required(decisionType, "decisionType");
        ModelChecks.required(decidedBy, "decidedBy", 128);
        ModelChecks.required(decidedAt, "decidedAt");
    }
}
