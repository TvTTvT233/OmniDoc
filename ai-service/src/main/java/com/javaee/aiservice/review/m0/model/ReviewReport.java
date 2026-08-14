package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record ReviewReport(
        String reportId, String caseId, String jobId, String contractVersion, String engineVersion,
        ReviewReportFreshness freshness, ReviewAssessment assessment, String inputFingerprint,
        String checklistSnapshotId, String uploadConfirmationId, long basisRevision,
        long materialSetRevision, long assignmentRevision, long nameRevision, String baseReportId,
        int missingCount, int fileNameNoncompliantCount, int cannotConfirmCount,
        Instant createdAt, Instant outdatedAt, String outdatedReason
) {
    public ReviewReport {
        ModelChecks.required(reportId, "reportId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(jobId, "jobId", 64);
        ModelChecks.required(contractVersion, "contractVersion", 64);
        ModelChecks.required(engineVersion, "engineVersion", 128);
        ModelChecks.required(freshness, "freshness");
        ModelChecks.required(assessment, "assessment");
        ModelChecks.digest(inputFingerprint, "inputFingerprint");
        ModelChecks.required(checklistSnapshotId, "checklistSnapshotId", 64);
        ModelChecks.required(uploadConfirmationId, "uploadConfirmationId", 64);
        if (missingCount < 0 || fileNameNoncompliantCount < 0 || cannotConfirmCount < 0) {
            throw ModelChecks.invalid("Report counts must not be negative");
        }
        if (freshness == ReviewReportFreshness.CURRENT && (outdatedAt != null || outdatedReason != null)) {
            throw ModelChecks.invalid("CURRENT reports cannot contain outdated metadata");
        }
        if (freshness == ReviewReportFreshness.OUTDATED && outdatedAt == null) {
            throw ModelChecks.invalid("OUTDATED reports require outdatedAt");
        }
        if (assessment == ReviewAssessment.M0_READY
                && (missingCount != 0 || fileNameNoncompliantCount != 0 || cannotConfirmCount != 0)) {
            throw ModelChecks.invalid("M0_READY requires zero immutable finding counts");
        }
        ModelChecks.required(createdAt, "createdAt");
    }
}
