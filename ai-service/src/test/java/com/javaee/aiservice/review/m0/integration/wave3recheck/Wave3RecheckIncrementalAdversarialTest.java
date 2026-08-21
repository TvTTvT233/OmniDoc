package com.javaee.aiservice.review.m0.integration.wave3recheck;

import com.javaee.aiservice.review.m0.evidence.EvidenceDraft;
import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.model.RequirementOrigin;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.review.BoundedRegexMatcher;
import com.javaee.aiservice.review.m0.review.CapturedReviewInput;
import com.javaee.aiservice.review.m0.review.DeterministicReviewEngine;
import com.javaee.aiservice.review.m0.review.FindingDraft;
import com.javaee.aiservice.review.m0.review.FormalChecklist;
import com.javaee.aiservice.review.m0.review.IncrementalPlan;
import com.javaee.aiservice.review.m0.review.IncrementalReviewValidator;
import com.javaee.aiservice.review.m0.review.ReviewInputManifest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent adversarial coverage for the FIX1 incremental impact set. */
class Wave3RecheckIncrementalAdversarialTest {
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);

    private final DeterministicReviewEngine engine = new DeterministicReviewEngine(
            new BoundedRegexMatcher(Duration.ofMillis(250)), new ReviewEvidenceFactory());
    private final IncrementalReviewValidator validator = new IncrementalReviewValidator();

    @Test
    void changedMaterialRefreshesEveryNamingFindingWhileUntouchedEvidenceIsBitStable() {
        FormalChecklist checklist = checklist(
                1, new FileNameRule(FileNameRuleKind.EXACT, "required.pdf", true));
        ReviewInputManifest base = manifest(
                List.of(
                        available("changed", "../before.pdf"),
                        available("untouched", "untouched-wrong.pdf")),
                List.of(assigned("changed"), assigned("untouched")),
                1);
        ReviewInputManifest renamed = manifest(
                List.of(
                        available("changed", "../after.pdf"),
                        available("untouched", "untouched-wrong.pdf")),
                base.assignments(),
                2);

        IncrementalPlan plan = validator.validate(base, renamed, List.of("changed"));
        List<FindingDraft> before = engine.evaluateFull(captured(checklist, base));
        List<FindingDraft> incremental = engine.evaluateIncremental(
                captured(checklist, renamed), before, plan);
        List<FindingDraft> full = engine.evaluateFull(captured(checklist, renamed));

        assertThat(plan.nameOnly()).isTrue();
        assertThat(plan.recomputeCompleteness()).isFalse();
        assertThat(findingsFor(incremental, "changed"))
                .hasSize(2)
                .allSatisfy(finding -> {
                    assertThat(materialEvidence(finding)).contains("../after.pdf");
                    assertThat(materialEvidence(finding)).doesNotContain("../before.pdf");
                });
        assertThat(findingsFor(incremental, "untouched")).singleElement().satisfies(reused -> {
            FindingDraft original = findingsFor(before, "untouched").get(0);
            assertThat(reused).isSameAs(original);
            assertThat(reused.evidence()).isEqualTo(original.evidence());
        });
        assertThat(canonical(incremental)).isEqualTo(canonical(full));
        assertThat(summary(incremental)).isEqualTo(summary(full));
    }

    @Test
    void cannotConfirmRenameRefreshesCurrentNameAndPreservesUnchangedMaterialEvidence() {
        FormalChecklist checklist = checklist(2, none());
        ReviewInputManifest base = manifest(
                List.of(
                        cannotConfirm("changed", "before.pdf", CannotConfirmReason.ENCRYPTED),
                        cannotConfirm("untouched", "stable.pdf", CannotConfirmReason.CORRUPTED)),
                List.of(),
                1);
        ReviewInputManifest renamed = manifest(
                List.of(
                        cannotConfirm("changed", "after.pdf", CannotConfirmReason.ENCRYPTED),
                        cannotConfirm("untouched", "stable.pdf", CannotConfirmReason.CORRUPTED)),
                List.of(),
                2);

        IncrementalPlan plan = validator.validate(base, renamed, List.of("changed"));
        List<FindingDraft> before = engine.evaluateFull(captured(checklist, base));
        List<FindingDraft> incremental = engine.evaluateIncremental(
                captured(checklist, renamed), before, plan);
        List<FindingDraft> full = engine.evaluateFull(captured(checklist, renamed));

        assertThat(plan.nameOnly()).isTrue();
        assertThat(plan.recomputeCompleteness()).isFalse();
        assertThat(findingsFor(incremental, "changed")).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(FindingType.CANNOT_CONFIRM);
            assertThat(materialEvidence(finding)).contains("after.pdf").doesNotContain("before.pdf");
        });
        assertThat(findingsFor(incremental, "untouched")).singleElement().satisfies(reused ->
                assertThat(reused).isSameAs(findingsFor(before, "untouched").get(0)));
        assertThat(canonical(incremental)).isEqualTo(canonical(full));
        assertThat(summary(incremental)).isEqualTo(new Summary(
                0, 0, 2, ReviewAssessment.M0_NOT_READY));
    }

    @Test
    void extensionCaseChangeStaysMinimalButTrueExtensionChangeExpandsGlobalSuppression() {
        FormalChecklist checklist = checklist(2, none());
        ReviewInputManifest base = manifest(
                List.of(available("changed", "before.pdf"), available("other", "other.pdf")),
                List.of(assigned("changed"), assigned("other")),
                1);
        ReviewInputManifest caseOnly = manifest(
                List.of(available("changed", "AFTER.PDF"), available("other", "other.pdf")),
                base.assignments(),
                2);
        IncrementalPlan casePlan = validator.validate(base, caseOnly, List.of("changed"));
        List<FindingDraft> caseIncremental = engine.evaluateIncremental(
                captured(checklist, caseOnly), engine.evaluateFull(captured(checklist, base)), casePlan);

        assertThat(casePlan.nameOnly()).isTrue();
        assertThat(casePlan.recomputeCompleteness()).isFalse();
        assertThat(canonical(caseIncremental))
                .isEqualTo(canonical(engine.evaluateFull(captured(checklist, caseOnly))));
        assertThat(summary(caseIncremental)).isEqualTo(new Summary(
                0, 0, 0, ReviewAssessment.M0_READY));

        ReviewInputManifest typeChanged = manifest(
                List.of(available("changed", "after.docx"), available("other", "other.pdf")),
                base.assignments(),
                3);
        IncrementalPlan typePlan = validator.validate(base, typeChanged, List.of("changed"));
        List<FindingDraft> typeIncremental = engine.evaluateIncremental(
                captured(checklist, typeChanged), engine.evaluateFull(captured(checklist, base)), typePlan);
        List<FindingDraft> typeFull = engine.evaluateFull(captured(checklist, typeChanged));

        assertThat(typePlan.nameOnly()).isTrue();
        assertThat(typePlan.recomputeCompleteness()).isTrue();
        assertThat(canonical(typeIncremental)).isEqualTo(canonical(typeFull));
        assertThat(summary(typeIncremental)).isEqualTo(new Summary(
                0, 0, 1, ReviewAssessment.M0_NOT_READY));
        assertThat(typeIncremental).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(FindingType.CANNOT_CONFIRM);
            assertThat(finding.cannotConfirmReason()).isEqualTo(CannotConfirmReason.TYPE_MISMATCH);
            assertThat(materialEvidence(finding)).contains("after.docx");
        });
    }

    @Test
    void typeMismatchMakesIncrementalAndFullDeeplyEquivalentAtEveryReportDimension() {
        FormalChecklist checklist = checklist(3, none());
        ReviewInputManifest base = manifest(
                List.of(available("changed", "one.pdf"), available("other", "two.pdf")),
                List.of(assigned("changed"), assigned("other")),
                1);
        ReviewInputManifest current = manifest(
                List.of(available("changed", "one.xlsx"), available("other", "two.pdf")),
                base.assignments(),
                2);

        IncrementalPlan plan = validator.validate(base, current, List.of("changed"));
        List<FindingDraft> incremental = engine.evaluateIncremental(
                captured(checklist, current), engine.evaluateFull(captured(checklist, base)), plan);
        List<FindingDraft> full = engine.evaluateFull(captured(checklist, current));

        assertThat(plan.recomputeCompleteness()).isTrue();
        assertThat(canonical(incremental)).isEqualTo(canonical(full));
        assertThat(incremental).usingRecursiveComparison().isEqualTo(full);
        assertThat(summary(incremental)).isEqualTo(summary(full));
        assertThat(summary(incremental)).isEqualTo(new Summary(
                0, 0, 1, ReviewAssessment.M0_NOT_READY));
        assertThat(incremental).noneMatch(finding -> finding.type() == FindingType.MISSING);
        assertThat(incremental).allSatisfy(finding ->
                assertThat(finding.evidence()).hasSize(2));
    }

    @Test
    void materialAssignmentOrderAndRepeatedIncrementalExecutionRemainCanonical() {
        FormalChecklist checklist = checklist(
                2, new FileNameRule(FileNameRuleKind.EXACT, "required.pdf", false));
        List<ReviewInputManifest.MaterialFact> baseMaterials = new ArrayList<>(List.of(
                available("changed", "before.pdf"),
                available("material-z", "wrong-z.pdf"),
                available("material-a", "wrong-a.pdf")));
        List<ReviewInputManifest.AssignmentFact> assignments = new ArrayList<>(List.of(
                assigned("material-z"), assigned("changed"), assigned("material-a")));
        ReviewInputManifest canonicalBase = manifest(baseMaterials, assignments, 1);
        List<ReviewInputManifest.MaterialFact> currentMaterials = new ArrayList<>(List.of(
                available("changed", "after.pdf"),
                available("material-z", "wrong-z.pdf"),
                available("material-a", "wrong-a.pdf")));
        ReviewInputManifest canonicalCurrent = manifest(currentMaterials, assignments, 2);
        List<String> expected = canonical(engine.evaluateFull(captured(checklist, canonicalCurrent)));
        Random random = new Random(305_031L);

        for (int run = 0; run < 40; run++) {
            Collections.shuffle(baseMaterials, random);
            Collections.shuffle(currentMaterials, random);
            Collections.shuffle(assignments, random);
            ReviewInputManifest shuffledBase = manifest(baseMaterials, assignments, 1);
            ReviewInputManifest shuffledCurrent = manifest(currentMaterials, assignments, 2);
            IncrementalPlan plan = validator.validate(
                    shuffledBase, shuffledCurrent, List.of("changed"));
            List<FindingDraft> incremental = engine.evaluateIncremental(
                    captured(checklist, shuffledCurrent),
                    engine.evaluateFull(captured(checklist, shuffledBase)),
                    plan);

            assertThat(shuffledBase.canonicalJson()).isEqualTo(canonicalBase.canonicalJson());
            assertThat(shuffledCurrent.canonicalJson()).isEqualTo(canonicalCurrent.canonicalJson());
            assertThat(canonical(incremental)).isEqualTo(expected);
            assertThat(canonical(engine.evaluateIncremental(
                    captured(checklist, shuffledCurrent),
                    engine.evaluateFull(captured(checklist, shuffledBase)),
                    plan))).isEqualTo(expected);
        }
    }

    private static FormalChecklist checklist(int minimumCount, FileNameRule rule) {
        return new FormalChecklist(
                "snapshot-1", "checklist-revision-1", 1, 7, DIGEST_A,
                List.of(new FormalChecklist.Item(
                        "requirement-1", RequirementApplicability.APPLIES, minimumCount, rule,
                        RequirementOrigin.USER_ADDED, RequirementAuthorship.USER_REWRITTEN,
                        3, "checklist-revision-1:requirement-1:3", List.of())));
    }

    private static FileNameRule none() {
        return new FileNameRule(FileNameRuleKind.NONE, null, null);
    }

    private static ReviewInputManifest manifest(
            List<ReviewInputManifest.MaterialFact> materials,
            List<ReviewInputManifest.AssignmentFact> assignments,
            long nameRevision
    ) {
        return new ReviewInputManifest(
                FormalChecklist.CONTRACT_VERSION,
                DeterministicReviewEngine.ENGINE_VERSION,
                "case-1",
                1,
                List.of(new ReviewInputManifest.BasisFact(
                        "basis-1", OfficialBasisRole.APPLICATION_NOTICE, DIGEST_B)),
                "snapshot-1",
                DIGEST_A,
                1,
                1,
                nameRevision,
                "upload-1",
                DIGEST_C,
                List.of(new ReviewInputManifest.SourceFact(
                        "source-1", FormalFileType.PDF, DIGEST_B, 100)),
                materials,
                assignments,
                List.of());
    }

    private static ReviewInputManifest.MaterialFact available(String materialId, String name) {
        return material(materialId, name, MaterialStatus.AVAILABLE, null);
    }

    private static ReviewInputManifest.MaterialFact cannotConfirm(
            String materialId,
            String name,
            CannotConfirmReason reason
    ) {
        return material(materialId, name, MaterialStatus.CANNOT_CONFIRM, reason);
    }

    private static ReviewInputManifest.MaterialFact material(
            String materialId,
            String name,
            MaterialStatus status,
            CannotConfirmReason reason
    ) {
        return new ReviewInputManifest.MaterialFact(
                materialId,
                "source-1",
                MaterialKind.TOP_LEVEL_FILE,
                null,
                null,
                DIGEST_A,
                FormalFileType.PDF,
                name,
                status,
                reason,
                reason == null ? null : "SYNTHETIC_STAGE");
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
                new MaterialCollectionSnapshot(
                        manifest.caseId(), manifest.materialSetRevision(), manifest.assignmentRevision(),
                        manifest.nameRevision(), List.of(), List.of(), List.of(), List.of(), null),
                manifest);
    }

    private static List<FindingDraft> findingsFor(List<FindingDraft> findings, String materialId) {
        return findings.stream().filter(item -> materialId.equals(item.materialId())).toList();
    }

    private static String materialEvidence(FindingDraft finding) {
        return finding.evidence().stream().map(EvidenceDraft::refJson).reduce("", String::concat);
    }

    private static List<String> canonical(List<FindingDraft> findings) {
        return findings.stream().map(FindingDraft::normalizedKey).sorted().toList();
    }

    private static Summary summary(List<FindingDraft> findings) {
        int missing = (int) findings.stream().filter(item -> item.type() == FindingType.MISSING).count();
        int naming = (int) findings.stream()
                .filter(item -> item.type() == FindingType.FILE_NAME_NONCOMPLIANT).count();
        int cannot = (int) findings.stream()
                .filter(item -> item.type() == FindingType.CANNOT_CONFIRM).count();
        return new Summary(
                missing,
                naming,
                cannot,
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
