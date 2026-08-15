package com.javaee.aiservice.review.m0.report;

import com.javaee.aiservice.review.m0.model.FindingDecision;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;

import java.util.List;

public record EffectiveReportView(
        PublishedReview published,
        List<FindingDecision> decisions,
        int ignoredCount,
        ReviewAssessment effectiveAssessment
) {
    public EffectiveReportView {
        decisions = List.copyOf(decisions);
    }
}
