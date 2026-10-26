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

    IdempotencyRecord completeWithSnapshot(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            String responseId,
            IdempotencyResponseSnapshot responseSnapshot
    );

    IdempotencyRecord attachSnapshotToCompletedInCallerTransaction(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            String responseId,
            IdempotencyResponseSnapshot responseSnapshot
    );

    Optional<IdempotencyRecord> find(IdempotencyScope scope);
}
