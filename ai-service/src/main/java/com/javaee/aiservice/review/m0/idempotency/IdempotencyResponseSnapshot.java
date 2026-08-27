package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ReviewM0KeyDigests;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, integrity-protected serialized HTTP success response. */
public record IdempotencyResponseSnapshot(
        int formatVersion,
        String responseBody,
        long responseBodyBytes,
        String snapshotDigest
) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int MAX_RESPONSE_BODY_BYTES = 256 * 1024;

    public static IdempotencyResponseSnapshot capture(
            int responseStatus,
            String responseType,
            String responseId,
            String responseBody
    ) {
        if (responseStatus < 200 || responseStatus > 299
                || responseType == null || responseType.isBlank() || responseType.length() > 64
                || responseId == null || responseId.isBlank() || responseId.length() > 64
                || responseBody == null) {
            throw unavailable("Idempotency response snapshot cannot be created", null);
        }
        long size = responseBody.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_RESPONSE_BODY_BYTES) {
            throw unavailable("Idempotency response snapshot exceeds the safety limit", null);
        }
        return new IdempotencyResponseSnapshot(
                CURRENT_FORMAT_VERSION,
                responseBody,
                size,
                digest(CURRENT_FORMAT_VERSION, responseStatus, responseType, responseId, responseBody));
    }

    public String requireValid(int responseStatus, String responseType, String responseId) {
        if (formatVersion != CURRENT_FORMAT_VERSION
                || responseStatus < 200 || responseStatus > 299
                || responseType == null || responseType.isBlank() || responseType.length() > 64
                || responseId == null || responseId.isBlank() || responseId.length() > 64
                || responseBody == null
                || responseBodyBytes < 0 || responseBodyBytes > MAX_RESPONSE_BODY_BYTES
                || responseBody.getBytes(StandardCharsets.UTF_8).length != responseBodyBytes
                || snapshotDigest == null || !snapshotDigest.matches("[0-9a-f]{64}")
                || !snapshotDigest.equals(digest(
                        formatVersion, responseStatus, responseType, responseId, responseBody))) {
            throw unavailable("Idempotency response snapshot failed integrity validation", null);
        }
        return responseBody;
    }

    static String scopeDigest(IdempotencyScope scope) {
        return ReviewM0KeyDigests.idempotencyScope(
                scope.ownerId(), scope.httpMethod(), scope.canonicalPath(), scope.idempotencyKey());
    }

    private static String digest(
            int formatVersion,
            int responseStatus,
            String responseType,
            String responseId,
            String responseBody
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("formatVersion", formatVersion);
        value.put("responseBody", responseBody);
        value.put("responseId", responseId);
        value.put("responseStatus", responseStatus);
        value.put("responseType", responseType);
        return CanonicalJson.sha256(value);
    }

    public static ReviewM0Exception unavailable(String message, Throwable cause) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE, message, cause);
    }
}
