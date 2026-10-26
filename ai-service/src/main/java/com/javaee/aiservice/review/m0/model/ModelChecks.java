package com.javaee.aiservice.review.m0.model;

import java.util.Objects;

final class ModelChecks {
    private ModelChecks() {
    }

    static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + " must be non-blank and at most " + maxLength + " characters");
        }
        return value;
    }

    static String digest(String value, String field) {
        required(value, field, 64);
        if (!value.matches("[0-9a-f]{64}")) {
            throw invalid(field + " must be a lower-case SHA-256 hex digest");
        }
        return value;
    }

    static long nonNegative(long value, String field) {
        if (value < 0) {
            throw invalid(field + " must not be negative");
        }
        return value;
    }

    static <T> T required(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }
}
