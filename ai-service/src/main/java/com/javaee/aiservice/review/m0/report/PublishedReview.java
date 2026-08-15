package com.javaee.aiservice.review.m0.report;

import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.review.FindingDraft;
import com.javaee.aiservice.review.m0.review.ReviewInputManifest;

import java.util.List;

public record PublishedReview(
        ReviewReport report,
        List<FindingDraft> findings,
        ReviewInputManifest inputManifest
) {
    public PublishedReview {
        findings = List.copyOf(findings);
    }
}
