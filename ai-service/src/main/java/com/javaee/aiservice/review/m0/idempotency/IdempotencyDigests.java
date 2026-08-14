package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.model.CanonicalJson;

public final class IdempotencyDigests {
    private IdempotencyDigests() {
    }

    public static String canonicalPayloadDigest(Object payload) {
        return CanonicalJson.sha256(payload);
    }
}
