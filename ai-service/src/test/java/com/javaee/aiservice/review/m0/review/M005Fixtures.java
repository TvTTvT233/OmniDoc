package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.model.RequirementOrigin;

import java.util.List;

final class M005Fixtures {
    static final String DIGEST_A = "a".repeat(64);
    static final String DIGEST_B = "b".repeat(64);
    static final String DIGEST_C = "c".repeat(64);

    private M005Fixtures() {
    }

    static FormalChecklist checklist(int minimumCount, FileNameRule rule) {
        return new FormalChecklist(
                "clsnap-1", "clrev-1", 1, 7, DIGEST_A,
                List.of(new FormalChecklist.Item(
                        "requirement-1", RequirementApplicability.APPLIES, minimumCount, rule,
                        RequirementOrigin.USER_ADDED, RequirementAuthorship.USER_REWRITTEN,
                        3, "clrev-1:requirement-1:3", List.of())));
    }

    static FileNameRule none() {
        return new FileNameRule(FileNameRuleKind.NONE, null, null);
    }

    static CapturedReviewInput input(
            FormalChecklist checklist,
            List<ReviewInputManifest.MaterialFact> materials,
            List<ReviewInputManifest.AssignmentFact> assignments,
            List<ReviewInputManifest.DiagnosticFact> diagnostics,
            long materialRevision,
            long assignmentRevision,
            long nameRevision,
            String uploadId
    ) {
        ReviewInputManifest manifest = new ReviewInputManifest(
                FormalChecklist.CONTRACT_VERSION,
                DeterministicReviewEngine.ENGINE_VERSION,
                "case-1",
                1,
                List.of(new ReviewInputManifest.BasisFact(
                        "basis-1", com.javaee.aiservice.review.m0.model.OfficialBasisRole.APPLICATION_NOTICE,
                        DIGEST_B)),
                checklist.snapshotId(),
                checklist.snapshotDigest(),
                materialRevision,
                assignmentRevision,
                nameRevision,
                uploadId,
                DIGEST_C,
                List.of(new ReviewInputManifest.SourceFact("source-1", FormalFileType.PDF, DIGEST_B, 100)),
                materials,
                assignments,
                diagnostics);
        return new CapturedReviewInput(
                checklist,
                new MaterialCollectionSnapshot(
                        "case-1", materialRevision, assignmentRevision, nameRevision,
                        List.of(), List.of(), List.of(), List.of(), null),
                manifest);
    }

    static ReviewInputManifest.MaterialFact available(String materialId, String name) {
        return new ReviewInputManifest.MaterialFact(
                materialId, "source-1", MaterialKind.TOP_LEVEL_FILE, null, null,
                DIGEST_A, FormalFileType.PDF, name, MaterialStatus.AVAILABLE, null, null);
    }

    static ReviewInputManifest.MaterialFact cannot(String materialId, CannotConfirmReason reason) {
        return new ReviewInputManifest.MaterialFact(
                materialId, "source-1", MaterialKind.TOP_LEVEL_FILE, null, null,
                DIGEST_A, FormalFileType.PDF, "input.pdf", MaterialStatus.CANNOT_CONFIRM,
                reason, "SYNTHETIC_STAGE");
    }

    static ReviewInputManifest.AssignmentFact assigned(String materialId) {
        return new ReviewInputManifest.AssignmentFact(
                materialId, "requirement-1", AssignmentStatus.USER_CONFIRMED);
    }

    static CapturedReviewInput empty(FormalChecklist checklist) {
        return input(checklist, List.of(), List.of(), List.of(), 0, 0, 0, "upload-1");
    }
}
