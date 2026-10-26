package com.javaee.aiservice.review.m0.model;

import java.util.List;

public record ReportBundle(
        ReviewReport report,
        List<ReviewFinding> findings,
        List<EvidenceRef> evidence
) {
    public ReportBundle {
        ModelChecks.required(report, "report");
        findings = List.copyOf(findings);
        evidence = List.copyOf(evidence);
    }
}
