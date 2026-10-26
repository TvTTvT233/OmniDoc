package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record OfficialBasis(
        String basisId, String caseId, String fileId, OfficialBasisRole role, OfficialBasisStatus status,
        String originalFileNameSnapshot, String declaredContentType, long fileSizeBytes, String fileDigest,
        String storageVersion, CannotConfirmReason cannotConfirmReason, String failureStage,
        long attachedBasisRevision, Long detachedBasisRevision, long version, Instant createdAt, Instant updatedAt
) {
    public OfficialBasis {
        ModelChecks.required(basisId, "basisId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(fileId, "fileId", 64);
        ModelChecks.required(role, "role");
        ModelChecks.required(status, "status");
        ModelChecks.required(originalFileNameSnapshot, "originalFileNameSnapshot", 1024);
        ModelChecks.nonNegative(fileSizeBytes, "fileSizeBytes");
        ModelChecks.digest(fileDigest, "fileDigest");
        if ((status == OfficialBasisStatus.CANNOT_CONFIRM) != (cannotConfirmReason != null)) {
            throw ModelChecks.invalid("Official-basis CANNOT_CONFIRM status and reason must agree");
        }
        ModelChecks.required(createdAt, "createdAt");
        ModelChecks.required(updatedAt, "updatedAt");
    }
}
