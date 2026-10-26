package com.javaee.aiservice.review.m0.idempotency;

public record IdempotencyClaim(IdempotencyClaimStatus status, IdempotencyRecord record) {
}
