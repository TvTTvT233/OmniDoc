package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalReviewRenameRegressionTest {
    private final IncrementalReviewValidator validator = new IncrementalReviewValidator();
    private final DeterministicReviewEngine engine = new DeterministicReviewEngine(
            new BoundedRegexMatcher(Duration.ofMillis(100)), new ReviewEvidenceFactory());

    @Test
    void sameExtensionRenameRefreshesCannotConfirmEvidenceAndReusesUnaffectedFinding() {
        FormalChecklist checklist = M005Fixtures.checklist(
                1, new FileNameRule(FileNameRuleKind.EXACT, "required.pdf", true));
        ReviewInputManifest base = manifest(
                List.of(
                        material("changed", "before.pdf", MaterialStatus.CANNOT_CONFIRM,
                                CannotConfirmReason.ENCRYPTED),
                        material("unaffected", "unaffected.pdf", MaterialStatus.AVAILABLE, null)),
                List.of(assigned("unaffected")), 1);
        ReviewInputManifest current = manifest(
                List.of(
                        material("changed", "after.pdf", MaterialStatus.CANNOT_CONFIRM,
                                CannotConfirmReason.ENCRYPTED),
                        material("unaffected", "unaffected.pdf", MaterialStatus.AVAILABLE, null)),
                base.assignments(), 2);
        CapturedReviewInput baseInput = captured(checklist, base);
        CapturedReviewInput currentInput = captured(checklist, current);
        List<FindingDraft> baseFindings = engine.evaluateFull(baseInput);
        IncrementalPlan plan = validator.validate(base, current, List.of("changed"));

        List<FindingDraft> incremental = engine.evaluateIncremental(currentInput, baseFindings, plan);
        List<FindingDraft> full = engine.evaluateFull(currentInput);

        assertThat(plan.nameOnly()).isTrue();
        assertThat(plan.recomputeCompleteness()).isFalse();
        assertThat(incremental).isEqualTo(full);
        assertThat(summary(incremental)).isEqualTo(summary(full));
        assertThat(summary(full)).isEqualTo(new Summary(0, 1, 1, ReviewAssessment.M0_NOT_READY));

        FindingDraft refreshed = finding(incremental, "changed", FindingType.CANNOT_CONFIRM);
        assertThat(refreshed.cannotConfirmReason()).isEqualTo(CannotConfirmReason.ENCRYPTED);
        assertThat(refreshed.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.refJson()).contains("\"submissionFileName\":\"after.pdf\"");
            assertThat(evidence.refJson()).doesNotContain("before.pdf");
        });

        FindingDraft baseUnaffected = finding(
                baseFindings, "unaffected", FindingType.FILE_NAME_NONCOMPLIANT);
        FindingDraft incrementalUnaffected = finding(
                incremental, "unaffected", FindingType.FILE_NAME_NONCOMPLIANT);
        assertThat(incrementalUnaffected).isSameAs(baseUnaffected);
        assertThat(incrementalUnaffected.normalizedKey()).isEqualTo(baseUnaffected.normalizedKey());
    }

    @Test
    void defensiveExtensionChangeExpandsCompletenessAndMatchesFullTypeMismatchReport() {
        FormalChecklist checklist = M005Fixtures.checklist(1, M005Fixtures.none());
        ReviewInputManifest base = manifest(
                List.of(material("changed", "before.pdf", MaterialStatus.AVAILABLE, null)),
                List.of(assigned("changed")), 1);
        ReviewInputManifest current = manifest(
                List.of(material("changed", "after.docx", MaterialStatus.AVAILABLE, null)),
                base.assignments(), 2);
        IncrementalPlan plan = validator.validate(base, current, List.of("changed"));

        List<FindingDraft> incremental = engine.evaluateIncremental(
                captured(checklist, current), engine.evaluateFull(captured(checklist, base)), plan);
        List<FindingDraft> full = engine.evaluateFull(captured(checklist, current));

        assertThat(plan.nameOnly()).isTrue();
        assertThat(plan.recomputeCompleteness()).isTrue();
        assertThat(incremental).isEqualTo(full);
        assertThat(summary(incremental)).isEqualTo(summary(full));
        assertThat(summary(full)).isEqualTo(new Summary(0, 0, 1, ReviewAssessment.M0_NOT_READY));
        assertThat(incremental).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(FindingType.CANNOT_CONFIRM);
            assertThat(finding.cannotConfirmReason()).isEqualTo(CannotConfirmReason.TYPE_MISMATCH);
            assertThat(finding.evidence()).anySatisfy(evidence ->
                    assertThat(evidence.refJson()).contains("\"submissionFileName\":\"after.docx\""));
        });
    }

    private static ReviewInputManifest manifest(
            List<ReviewInputManifest.MaterialFact> materials,
            List<ReviewInputManifest.AssignmentFact> assignments,
            long nameRevision
    ) {
        return new ReviewInputManifest(
                FormalChecklist.CONTRACT_VERSION, DeterministicReviewEngine.ENGINE_VERSION,
                "case-1", 1,
                List.of(new ReviewInputManifest.BasisFact(
                        "basis-1",
                        com.javaee.aiservice.review.m0.model.OfficialBasisRole.APPLICATION_NOTICE,
                        M005Fixtures.DIGEST_B)),
                "clsnap-1", M005Fixtures.DIGEST_A,
                1, 1, nameRevision, "upload-1", M005Fixtures.DIGEST_C,
                List.of(new ReviewInputManifest.SourceFact(
                        "source-1", FormalFileType.PDF, M005Fixtures.DIGEST_B, 100)),
                materials, assignments, List.of());
    }

    private static ReviewInputManifest.MaterialFact material(
            String materialId,
            String submissionFileName,
            MaterialStatus status,
            CannotConfirmReason reason
    ) {
        return new ReviewInputManifest.MaterialFact(
                materialId, "source-1", MaterialKind.TOP_LEVEL_FILE, null, null,
                M005Fixtures.DIGEST_A, FormalFileType.PDF, submissionFileName,
                status, reason, reason == null ? null : "SYNTHETIC_STAGE");
    }

    private static ReviewInputManifest.AssignmentFact assigned(String materialId) {
        return new ReviewInputManifest.AssignmentFact(
                materialId, "requirement-1", AssignmentStatus.USER_CONFIRMED);
    }

    private static CapturedReviewInput captured(
            FormalChecklist checklist,
            ReviewInputManifest manifest
    ) {
        return new CapturedReviewInput(
                checklist,
                new com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot(
                        manifest.caseId(), manifest.materialSetRevision(), manifest.assignmentRevision(),
                        manifest.nameRevision(), List.of(), List.of(), List.of(), List.of(), null),
                manifest);
    }

    private static FindingDraft finding(
            List<FindingDraft> findings,
            String materialId,
            FindingType type
    ) {
        return findings.stream()
                .filter(item -> materialId.equals(item.materialId()) && item.type() == type)
                .findFirst().orElseThrow();
    }

    private static Summary summary(List<FindingDraft> findings) {
        int missing = (int) findings.stream().filter(item -> item.type() == FindingType.MISSING).count();
        int names = (int) findings.stream()
                .filter(item -> item.type() == FindingType.FILE_NAME_NONCOMPLIANT).count();
        int cannot = (int) findings.stream()
                .filter(item -> item.type() == FindingType.CANNOT_CONFIRM).count();
        return new Summary(missing, names, cannot,
                findings.isEmpty() ? ReviewAssessment.M0_READY : ReviewAssessment.M0_NOT_READY);
    }

    private record Summary(
            int missing,
            int fileNameNoncompliant,
            int cannotConfirm,
            ReviewAssessment assessment
    ) {
    }
}
