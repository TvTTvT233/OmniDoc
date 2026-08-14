package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record SubmissionSource(
        String sourceId, String caseId, String fileId, String originalFileNameSnapshot,
        String declaredContentType, FormalFileType formalFileType, SubmissionSourceStatus status,
        long fileSizeBytes, String fileDigest, String storageVersion, long attachedMaterialSetRevision,
        Long removedMaterialSetRevision, long version, Instant createdAt, Instant updatedAt
) {
    public SubmissionSource {
        ModelChecks.required(sourceId, "sourceId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(fileId, "fileId", 64);
        ModelChecks.required(originalFileNameSnapshot, "originalFileNameSnapshot", 1024);
        ModelChecks.required(formalFileType, "formalFileType");
        ModelChecks.required(status, "status");
        ModelChecks.digest(fileDigest, "fileDigest");
        ModelChecks.required(createdAt, "createdAt");
        ModelChecks.required(updatedAt, "updatedAt");
    }
}
