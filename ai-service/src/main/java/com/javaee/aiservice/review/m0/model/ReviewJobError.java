package com.javaee.aiservice.review.m0.model;

public record ReviewJobError(ReviewM0ErrorCode errorCode, boolean retryable, String message) {
    public ReviewJobError {
        ModelChecks.required(errorCode, "errorCode");
        ModelChecks.required(message, "message", 1024);
    }
}
