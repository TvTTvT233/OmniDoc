package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;

import java.time.Instant;
import java.util.List;

public record ChecklistDraftView(
        String checklistRevisionId,
        String caseId,
        ChecklistLifecycle lifecycle,
        long basisRevision,
        long revision,
        String draftDigest,
        String extractionJobId,
        String createdBy,
        Instant createdAt,
        List<ChecklistDraftItem> items
) {
    public ChecklistDraftView {
        items = List.copyOf(items);
    }
}
