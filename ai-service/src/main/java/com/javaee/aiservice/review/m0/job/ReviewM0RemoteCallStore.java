package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.RemoteCall;

import java.time.Instant;
import java.util.Optional;

public interface ReviewM0RemoteCallStore {
    RemoteCall beginSendIntent(RemoteCall intent, String workerId, Instant now);

    RemoteCall recordResponse(String jobId, String workerId, String providerRequestId, String responseDigest, Instant now);

    RemoteCall markOutcomeUnknown(String jobId, String workerId, Instant now);

    Optional<RemoteCall> findByJob(String jobId);
}
