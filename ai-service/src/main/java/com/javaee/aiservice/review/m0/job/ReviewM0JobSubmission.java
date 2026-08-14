package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;

public record ReviewM0JobSubmission(ReviewJob job, int responseStatus, boolean replayed) {
}
