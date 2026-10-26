package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record RemotePreview(
        String previewId, String caseId, ReviewJobType operation, RemotePreviewStatus status,
        long basisRevision, String segmentsJson, String payloadDigest, long version,
        String createdBy, Instant createdAt, Instant updatedAt
) {
    public RemotePreview {
        ModelChecks.required(previewId, "previewId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        if (operation != ReviewJobType.CHECKLIST_EXTRACTION) {
            throw ModelChecks.invalid("Remote previews only support CHECKLIST_EXTRACTION");
        }
        ModelChecks.required(status, "status");
        ModelChecks.required(segmentsJson, "segmentsJson", Integer.MAX_VALUE);
        ModelChecks.digest(payloadDigest, "payloadDigest");
        ModelChecks.required(createdBy, "createdBy", 128);
        ModelChecks.required(createdAt, "createdAt");
        ModelChecks.required(updatedAt, "updatedAt");
    }
}
