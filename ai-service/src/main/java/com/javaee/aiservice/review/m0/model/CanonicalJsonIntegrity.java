package com.javaee.aiservice.review.m0.model;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class CanonicalJsonIntegrity {
    private static final ObjectMapper READER = new ObjectMapper();

    private CanonicalJsonIntegrity() {
    }

    public static void requireValid(String canonicalJson, String digest, String subject) {
        if (canonicalJson == null || digest == null) {
            throw invalid(subject + " JSON and digest are required", null);
        }
        try {
            Object parsed = READER.readValue(canonicalJson, Object.class);
            String normalized = CanonicalJson.write(parsed);
            if (!normalized.equals(canonicalJson) || !CanonicalJson.sha256(parsed).equals(digest)) {
                throw invalid(subject + " JSON is not canonical or its digest does not match", null);
            }
        } catch (ReviewM0Exception error) {
            throw error;
        } catch (Exception error) {
            throw invalid(subject + " JSON cannot be validated", error);
        }
    }

    private static ReviewM0Exception invalid(String message, Throwable cause) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message, cause);
    }
}
