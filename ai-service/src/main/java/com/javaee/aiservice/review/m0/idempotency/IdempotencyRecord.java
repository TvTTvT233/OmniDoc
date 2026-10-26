package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.model.IdempotencyState;

import java.time.Instant;

public record IdempotencyRecord(
        IdempotencyScope scope,
        String requestDigest,
        Integer responseStatus,
        String responseType,
        String responseId,
        IdempotencyResponseSnapshot responseSnapshot,
        IdempotencyState state,
        Instant createdAt,
        Instant updatedAt
) {
}
