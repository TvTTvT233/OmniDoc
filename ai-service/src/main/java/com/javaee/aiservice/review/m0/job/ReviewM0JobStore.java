package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobCheckpoint;
import com.javaee.aiservice.review.m0.model.ReviewJobError;
import com.javaee.aiservice.review.m0.model.ReviewJobResultRef;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface ReviewM0JobStore {
    ReviewJob create(ReviewJob job);

    Optional<ReviewJob> findOwned(String ownerId, String caseId, String jobId);

    Optional<ReviewJob> acquireNext(String workerId, Instant now, Duration leaseDuration);

    ReviewJob heartbeat(String jobId, String workerId, long expectedVersion, Instant now, Duration leaseDuration);

    ReviewJob releaseForRetry(String jobId, String workerId, long expectedVersion, Instant now);

    CheckpointWrite saveCheckpoint(
            String jobId,
            String workerId,
            String checkpointKey,
            String checkpointJson,
            String checkpointDigest,
            long completedUnits,
            long totalUnits,
            Instant now,
            Duration leaseDuration
    );

    List<ReviewJobCheckpoint> listCheckpoints(String jobId);

    ReviewJob requestCancelOwned(String ownerId, String caseId, String jobId, Instant now);

    ReviewJob finalizeCancellation(String jobId, String workerId, Instant now);

    ReviewJob fail(String jobId, String workerId, ReviewJobError error, Instant now);

    ReviewJob completeWithResult(
            String jobId,
            String workerId,
            Instant now,
            Supplier<ReviewJobResultRef> databasePublication
    );
}
