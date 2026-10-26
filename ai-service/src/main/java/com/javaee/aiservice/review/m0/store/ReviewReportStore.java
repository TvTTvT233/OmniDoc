package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewReport;

import java.time.Instant;
import java.util.Optional;

public interface ReviewReportStore {
    Optional<ReviewReport> findOwned(String ownerId, String caseId, String reportId);

    Optional<ReviewReport> findCurrentOwned(String ownerId, String caseId);

    ReviewJob publishOwned(
            String ownerId,
            String workerId,
            Instant now,
            ReportBundle bundle
    );
}
