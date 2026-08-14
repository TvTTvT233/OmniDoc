package com.javaee.aiservice.review.m0.idempotency;

import java.util.Optional;

public interface ReviewM0IdempotencyStore {
    IdempotencyClaim claim(IdempotencyScope scope, String requestDigest);

    IdempotencyRecord complete(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            String responseId
    );

    Optional<IdempotencyRecord> find(IdempotencyScope scope);
}
