package com.javaee.aiservice.review.m0.model;

import java.util.List;

public record ReviewInputFingerprintSpec(
        String contractVersion,
        String caseId,
        long basisRevision,
        List<DigestEntry> basisDigests,
        String checklistSnapshotId,
        String checklistSnapshotDigest,
        long materialSetRevision,
        List<DigestEntry> materialDigests,
        long assignmentRevision,
        List<ConfirmedAssignmentFingerprint> confirmedAssignments,
        long nameRevision,
        List<LogicalNameFingerprint> logicalFileNames,
        String uploadConfirmationId,
        String engineVersion
) {
    public ReviewInputFingerprintSpec {
        ModelChecks.required(contractVersion, "contractVersion", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(checklistSnapshotId, "checklistSnapshotId", 64);
        ModelChecks.digest(checklistSnapshotDigest, "checklistSnapshotDigest");
        ModelChecks.required(uploadConfirmationId, "uploadConfirmationId", 64);
        ModelChecks.required(engineVersion, "engineVersion", 128);
        basisDigests = List.copyOf(basisDigests);
        materialDigests = List.copyOf(materialDigests);
        confirmedAssignments = List.copyOf(confirmedAssignments);
        logicalFileNames = List.copyOf(logicalFileNames);
    }
}
