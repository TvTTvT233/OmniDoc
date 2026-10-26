package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record RemoteCall(
        String remoteCallId, String jobId, String consentId, String requestDigest,
        RemoteCallState state, String providerRequestId, String responseDigest,
        Instant createdAt, Instant updatedAt
) {
    public RemoteCall {
        ModelChecks.required(remoteCallId, "remoteCallId", 64);
        ModelChecks.required(jobId, "jobId", 64);
        ModelChecks.required(consentId, "consentId", 64);
        ModelChecks.digest(requestDigest, "requestDigest");
        ModelChecks.required(state, "state");
        if ((state == RemoteCallState.RESPONSE_RECORDED) != (responseDigest != null)) {
            throw ModelChecks.invalid("Only RESPONSE_RECORDED calls require a response digest");
        }
        if (responseDigest != null) {
            ModelChecks.digest(responseDigest, "responseDigest");
        }
        ModelChecks.required(createdAt, "createdAt");
        ModelChecks.required(updatedAt, "updatedAt");
    }
}
