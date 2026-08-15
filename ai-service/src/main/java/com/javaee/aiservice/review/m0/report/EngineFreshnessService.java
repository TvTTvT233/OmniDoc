package com.javaee.aiservice.review.m0.report;

import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import com.javaee.aiservice.review.m0.store.ReviewReportStore;

import java.util.Objects;

public final class EngineFreshnessService {
    private final ReviewCaseStore caseStore;
    private final ReviewReportStore reportStore;

    public EngineFreshnessService(ReviewCaseStore caseStore, ReviewReportStore reportStore) {
        this.caseStore = Objects.requireNonNull(caseStore);
        this.reportStore = Objects.requireNonNull(reportStore);
    }

    /** Invalidates, but never revives, a report produced by a different deterministic engine version. */
    public void invalidateDifferentEngineOwned(String ownerId, String caseId, String currentEngineVersion) {
        ReviewReport report = reportStore.findCurrentOwned(ownerId, caseId).orElse(null);
        if (report == null || report.engineVersion().equals(currentEngineVersion)) return;
        ReviewCase reviewCase = caseStore.findOwned(ownerId, caseId).orElseThrow();
        caseStore.advanceRevisionOwned(
                ownerId, caseId, reviewCase.version(), RevisionKind.ENGINE_VERSION,
                "REVIEW_ENGINE_VERSION_CHANGED");
    }
}
