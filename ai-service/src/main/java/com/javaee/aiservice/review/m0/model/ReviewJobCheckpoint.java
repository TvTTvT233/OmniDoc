package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record ReviewJobCheckpoint(
        String jobId, String checkpointKey, String checkpointJson,
        String checkpointDigest, long version, Instant updatedAt
) {
    public ReviewJobCheckpoint {
        ModelChecks.required(jobId, "jobId", 64);
        ModelChecks.required(checkpointKey, "checkpointKey", 255);
        ModelChecks.required(checkpointJson, "checkpointJson", Integer.MAX_VALUE);
        ModelChecks.digest(checkpointDigest, "checkpointDigest");
        ModelChecks.nonNegative(version, "version");
        ModelChecks.required(updatedAt, "updatedAt");
    }
}
