package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.job.ReviewM0JobHandler;
import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewMode;

import java.util.Objects;

/** M0-05 registration for the M0-02 FORMAL_REVIEW SPI slot. */
public final class FormalReviewJobHandler implements ReviewM0JobHandler {
    private final ReviewM0ReviewJobExecutor executor;

    public FormalReviewJobHandler(ReviewM0ReviewJobExecutor executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public ReviewJobType jobType() {
        return ReviewJobType.FORMAL_REVIEW;
    }

    @Override
    public void handle(ReviewJob leasedJob, ReviewM0JobStore jobStore) {
        executor.execute(leasedJob, jobStore, ReviewMode.FULL);
    }
}
