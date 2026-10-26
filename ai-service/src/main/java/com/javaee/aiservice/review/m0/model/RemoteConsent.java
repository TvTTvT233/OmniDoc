package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record RemoteConsent(
        String consentId, String previewId, String caseId, ReviewJobType operation,
        long basisRevision, String payloadDigest, String confirmedBy, Instant confirmedAt,
        String consumedByJobId, Instant consumedAt
) {
    public RemoteConsent {
        ModelChecks.required(consentId, "consentId", 64);
        ModelChecks.required(previewId, "previewId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        if (operation != ReviewJobType.CHECKLIST_EXTRACTION) {
            throw ModelChecks.invalid("Remote consent only supports CHECKLIST_EXTRACTION");
        }
        ModelChecks.digest(payloadDigest, "payloadDigest");
        ModelChecks.required(confirmedBy, "confirmedBy", 128);
        ModelChecks.required(confirmedAt, "confirmedAt");
        if ((consumedByJobId == null) != (consumedAt == null)) {
            throw ModelChecks.invalid("Remote-consent consumer and consumedAt must both be set or null");
        }
    }
}
