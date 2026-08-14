package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobType;

public interface ReviewM0JobHandler {
    ReviewJobType jobType();

    void handle(ReviewJob leasedJob, ReviewM0JobStore jobStore);
}
