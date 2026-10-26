package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record ReviewFinding(
        String findingId, String reportId, FindingType findingType, CannotConfirmReason cannotConfirmReason,
        String requirementId, String materialId, String stableFindingKey, String message, Instant createdAt
) {
    public ReviewFinding {
        ModelChecks.required(findingId, "findingId", 64);
        ModelChecks.required(reportId, "reportId", 64);
        ModelChecks.required(findingType, "findingType");
        ModelChecks.digest(stableFindingKey, "stableFindingKey");
        ModelChecks.required(message, "message", 2048);
        if ((findingType == FindingType.CANNOT_CONFIRM) != (cannotConfirmReason != null)) {
            throw ModelChecks.invalid("CANNOT_CONFIRM findings and reasons must agree");
        }
        ModelChecks.required(createdAt, "createdAt");
    }
}
