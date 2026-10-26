package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobCheckpoint;

public record CheckpointWrite(ReviewJob job, ReviewJobCheckpoint checkpoint, boolean replayed) {
}
