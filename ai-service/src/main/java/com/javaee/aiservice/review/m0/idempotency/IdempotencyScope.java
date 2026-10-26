package com.javaee.aiservice.review.m0.idempotency;

import java.util.Locale;

public record IdempotencyScope(
        String ownerId,
        String httpMethod,
        String canonicalPath,
        String idempotencyKey
) {
    public IdempotencyScope {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 128
                || httpMethod == null || httpMethod.isBlank() || httpMethod.length() > 16
                || canonicalPath == null || !canonicalPath.startsWith("/") || canonicalPath.length() > 1024
                || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency scope is invalid");
        }
        httpMethod = httpMethod.toUpperCase(Locale.ROOT);
    }
}
