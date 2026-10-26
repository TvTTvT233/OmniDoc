package com.javaee.aiservice.review.m0.report;

import java.util.Optional;

public interface ReviewReportReader {
    Optional<PublishedReview> findOwned(String ownerId, String caseId, String reportId);

    default PublishedReview requireOwned(String ownerId, String caseId, String reportId) {
        return findOwned(ownerId, caseId, reportId).orElseThrow(() ->
                new com.javaee.aiservice.review.m0.model.ReviewM0Exception(
                        com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                        "Review report was not found"));
    }
}
