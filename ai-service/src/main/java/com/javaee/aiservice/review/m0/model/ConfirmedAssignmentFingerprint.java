package com.javaee.aiservice.review.m0.model;

public record ConfirmedAssignmentFingerprint(String materialId, String requirementId) {
    public ConfirmedAssignmentFingerprint {
        ModelChecks.required(materialId, "materialId", 64);
        ModelChecks.required(requirementId, "requirementId", 64);
    }

    public String stableId() {
        return materialId + "\u0000" + requirementId;
    }
}
