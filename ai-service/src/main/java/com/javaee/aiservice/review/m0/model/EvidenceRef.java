package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record EvidenceRef(
        String evidenceId, String findingId, EvidenceSide evidenceSide, EvidenceKind evidenceKind,
        String refJson, String refDigest, Instant createdAt
) {
    public EvidenceRef {
        ModelChecks.required(evidenceId, "evidenceId", 64);
        ModelChecks.required(findingId, "findingId", 64);
        ModelChecks.required(evidenceSide, "evidenceSide");
        ModelChecks.required(evidenceKind, "evidenceKind");
        ModelChecks.required(refJson, "refJson", Integer.MAX_VALUE);
        ModelChecks.digest(refDigest, "refDigest");
        ModelChecks.required(createdAt, "createdAt");
    }
}
