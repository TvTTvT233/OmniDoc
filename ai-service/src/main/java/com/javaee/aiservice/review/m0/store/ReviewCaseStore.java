package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.RevisionKind;

import java.util.Optional;

public interface ReviewCaseStore {
    ReviewCase create(ReviewCase reviewCase);

    Optional<ReviewCase> findOwned(String ownerId, String caseId);

    Optional<ChecklistSnapshot> findChecklistSnapshotOwned(
            String ownerId,
            String caseId,
            String checklistSnapshotId
    );

    ReviewCase advanceRevisionOwned(
            String ownerId,
            String caseId,
            long expectedVersion,
            RevisionKind revisionKind,
            String outdatedReason
    );

    ChecklistSnapshot publishChecklistSnapshotOwned(
            String ownerId,
            String caseId,
            long expectedVersion,
            ChecklistSnapshot snapshot,
            String outdatedReason
    );
}
