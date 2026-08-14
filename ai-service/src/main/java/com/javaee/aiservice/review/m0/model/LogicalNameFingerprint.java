package com.javaee.aiservice.review.m0.model;

public record LogicalNameFingerprint(String materialId, String submissionFileName) {
    public LogicalNameFingerprint {
        ModelChecks.required(materialId, "materialId", 64);
        submissionFileName = new LogicalFileName(submissionFileName).original();
    }
}
