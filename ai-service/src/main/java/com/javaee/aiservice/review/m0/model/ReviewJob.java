package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record ReviewJob(
        String jobId, String caseId, String userId, ReviewJobType jobType, ReviewJobStatus status,
        String requestJson, String requestDigest, String inputFingerprint, String idempotencyKey,
        long progressCompleted, long progressTotal, int attempt, String leaseOwner, Instant leaseExpiresAt,
        Instant cancelRequestedAt, ReviewJobResultRef result, ReviewJobError error, long version,
        Instant createdAt, Instant startedAt, Instant heartbeatAt, Instant finishedAt
) {
    public ReviewJob {
        ModelChecks.required(jobId, "jobId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(userId, "userId", 128);
        ModelChecks.required(jobType, "jobType");
        ModelChecks.required(status, "status");
        ModelChecks.required(requestJson, "requestJson", Integer.MAX_VALUE);
        ModelChecks.digest(requestDigest, "requestDigest");
        if (inputFingerprint != null) {
            ModelChecks.digest(inputFingerprint, "inputFingerprint");
        }
        ModelChecks.required(idempotencyKey, "idempotencyKey", 255);
        if (progressCompleted < 0 || progressTotal < 0 || progressCompleted > progressTotal || attempt < 0) {
            throw ModelChecks.invalid("Job progress or attempt is invalid");
        }
        if ((leaseOwner == null) != (leaseExpiresAt == null)) {
            throw ModelChecks.invalid("Job lease owner and expiry must both be set or null");
        }
        if (status == ReviewJobStatus.SUCCEEDED && result == null
                || status != ReviewJobStatus.SUCCEEDED && result != null) {
            throw ModelChecks.invalid("Only SUCCEEDED jobs can contain a result reference");
        }
        if (status == ReviewJobStatus.FAILED && error == null
                || status != ReviewJobStatus.FAILED && error != null) {
            throw ModelChecks.invalid("Only FAILED jobs can contain an error");
        }
        if (status.terminal() && finishedAt == null) {
            throw ModelChecks.invalid("Terminal jobs require finishedAt");
        }
        ModelChecks.required(createdAt, "createdAt");
    }
}
