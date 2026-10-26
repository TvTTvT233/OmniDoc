package com.javaee.aiservice.review.m0.review;

public interface ReviewInputProvider {
    CapturedReviewInput captureForSubmission(
            String ownerId, String caseId, String checklistSnapshotId, String uploadConfirmationId);

    CapturedReviewInput captureForExecution(
            String ownerId, String caseId, String checklistSnapshotId, String uploadConfirmationId);
}
