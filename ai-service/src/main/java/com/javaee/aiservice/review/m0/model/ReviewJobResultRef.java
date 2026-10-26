package com.javaee.aiservice.review.m0.model;

public record ReviewJobResultRef(String resultType, String resultId) {
    public ReviewJobResultRef {
        ModelChecks.required(resultType, "resultType", 64);
        ModelChecks.required(resultId, "resultId", 64);
    }
}
