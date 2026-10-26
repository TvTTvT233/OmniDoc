package com.javaee.aiservice.review.m0.model;

import java.time.Instant;

public record ChecklistSnapshot(
        String checklistSnapshotId, String caseId, String checklistRevisionId, long basisRevision,
        long checklistRevision, String snapshotJson, String snapshotDigest, ChecklistLifecycle lifecycle,
        String confirmedBy, Instant confirmedAt, Instant supersededAt
) {
    public ChecklistSnapshot {
        ModelChecks.required(checklistSnapshotId, "checklistSnapshotId", 64);
        ModelChecks.required(caseId, "caseId", 64);
        ModelChecks.required(checklistRevisionId, "checklistRevisionId", 64);
        ModelChecks.required(snapshotJson, "snapshotJson", Integer.MAX_VALUE);
        ModelChecks.digest(snapshotDigest, "snapshotDigest");
        if (lifecycle == ChecklistLifecycle.CANDIDATE_DRAFT) {
            throw ModelChecks.invalid("Immutable checklist snapshots cannot be candidate drafts");
        }
        ModelChecks.required(confirmedBy, "confirmedBy", 128);
        ModelChecks.required(confirmedAt, "confirmedAt");
    }
}
