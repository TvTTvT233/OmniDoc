package com.javaee.aiservice.review.m0.integration.wave3;

import com.javaee.aiservice.review.m0.evidence.EvidenceDraft;
import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.DiagnosticSubjectType;
import com.javaee.aiservice.review.m0.model.EvidenceSide;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.model.RequirementOrigin;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.review.BoundedRegexMatcher;
import com.javaee.aiservice.review.m0.review.CapturedReviewInput;
import com.javaee.aiservice.review.m0.review.DeterministicReviewEngine;
import com.javaee.aiservice.review.m0.review.FindingDraft;
import com.javaee.aiservice.review.m0.review.FormalChecklist;
import com.javaee.aiservice.review.m0.review.FormalReviewJobHandler;
import com.javaee.aiservice.review.m0.review.IncrementalPlan;
import com.javaee.aiservice.review.m0.review.IncrementalReviewJobHandler;
import com.javaee.aiservice.review.m0.review.IncrementalReviewValidator;
import com.javaee.aiservice.review.m0.review.ReviewInputManifest;
import com.javaee.aiservice.review.m0.review.ReviewM0ReviewJobExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent Wave 3 contract gate over the M0-05 deterministic core. */
class Wave3DeterministicReviewGateTest {
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);

    private final DeterministicReviewEngine engine = new DeterministicReviewEngine(
            new BoundedRegexMatcher(Duration.ofMillis(250)), new ReviewEvidenceFactory());
    private final IncrementalReviewValidator incremental = new IncrementalReviewValidator();

    @ParameterizedTest
    @EnumSource(CannotConfirmReason.class)
    void everyFrozenCannotConfirmReasonSuppressesMissingAcrossTheWholeUnassignedCollection(
            CannotConfirmReason reason
    ) {
        FormalChecklist checklist = checklist(2, none(), false);
        CapturedReviewInput input = captured(checklist, manifest(
                List.of(material("uncertain", "unknown-use.pdf", MaterialStatus.CANNOT_CONFIRM, reason)),
                List.of(),
                List.of(),
                1, 0, 0, "upload-1"));

        List<FindingDraft> findings = engine.evaluateFull(input);

        assertThat(findings).hasSize(1);
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.type()).isEqualTo(FindingType.CANNOT_CONFIRM);
            assertThat(finding.cannotConfirmReason()).isEqualTo(reason);
        });
        assertThat(findings).noneMatch(finding -> finding.type() == FindingType.MISSING);
    }

    @Test
    void deterministicFindingsCarryBothEvidenceSidesAndNoBodyOrRemotePayloadFields() {
        FormalChecklist official = checklist(
                1, new FileNameRule(FileNameRuleKind.EXACT, "required.pdf", true), true);
        FindingDraft missing = engine.evaluateFull(captured(official, manifest(
                List.of(), List.of(), List.of(), 0, 0, 0, "upload-empty"))).get(0);
        assertDualEvidence(missing);
        assertThat(missing.evidence()).anySatisfy(item -> {
            if (item.side() == EvidenceSide.RULE) {
                assertThat(item.refJson()).contains(
                        "checklistSnapshotId", "checklistRevisionId", "minimumCount",
                        "ruleRevision", "officialSourceRef");
            }
        });
        assertThat(missing.evidence()).anySatisfy(item -> {
            if (item.side() == EvidenceSide.MATERIAL) {
                assertThat(item.refJson()).contains(
                        "uploadConfirmationId", "materials", "sources",
                        "confirmedAssignments", "confirmedAvailableCount", "expectedCount");
            }
        });

        ReviewInputManifest.MaterialFact named = available("material-1", "wrong.pdf");
        FindingDraft naming = engine.evaluateFull(captured(official, manifest(
                        List.of(named), List.of(assigned("material-1")), List.of(),
                        1, 1, 1, "upload-named"))).stream()
                .filter(item -> item.type() == FindingType.FILE_NAME_NONCOMPLIANT)
                .findFirst().orElseThrow();
        assertDualEvidence(naming);
        assertThat(naming.evidence()).anySatisfy(item -> {
            if (item.side() == EvidenceSide.RULE) {
                assertThat(item.refJson()).contains("ruleId", "ruleRevision", "fileNameRule");
            }
        });
        assertThat(naming.evidence()).anySatisfy(item -> {
            if (item.side() == EvidenceSide.MATERIAL) {
                assertThat(item.refJson()).contains(
                        "materialId", "submissionFileName", "contentDigest", "formalFileType");
            }
        });

        String allEvidence = (missing.evidence().stream().map(EvidenceDraft::refJson)
                .reduce("", String::concat)
                + naming.evidence().stream().map(EvidenceDraft::refJson).reduce("", String::concat))
                .toLowerCase(java.util.Locale.ROOT);
        assertThat(allEvidence)
                .doesNotContain("official requirement full body")
                .doesNotContain("material body marker")
                .doesNotContain("objectkey")
                .doesNotContain("presigned")
                .doesNotContain("previewtext")
                .doesNotContain("remotepayload");
    }

    @Test
    void exactRegexNoneCaseExtensionSafetyAndTypeMismatchRemainDistinct() {
        assertThat(findingsFor(
                new FileNameRule(FileNameRuleKind.EXACT, "Form.pdf", true), "form.pdf"))
                .extracting(FindingDraft::type)
                .containsExactly(FindingType.FILE_NAME_NONCOMPLIANT);
        assertThat(findingsFor(
                new FileNameRule(FileNameRuleKind.EXACT, "Form.pdf", false), "form.pdf"))
                .isEmpty();
        assertThat(findingsFor(
                new FileNameRule(FileNameRuleKind.REGEX, "FORM-[0-9]+\\.PDF", false),
                "form-12.PDF")).isEmpty();
        assertThat(findingsFor(none(), "safe.PDF")).isEmpty();
        assertThat(findingsFor(none(), "../unsafe.pdf"))
                .extracting(FindingDraft::type)
                .containsExactly(FindingType.FILE_NAME_NONCOMPLIANT);

        List<FindingDraft> mismatch = findingsFor(none(), "wrong.docx");
        assertThat(mismatch).hasSize(1);
        assertThat(mismatch.get(0).type()).isEqualTo(FindingType.CANNOT_CONFIRM);
        assertThat(mismatch.get(0).cannotConfirmReason()).isEqualTo(CannotConfirmReason.TYPE_MISMATCH);

        DeterministicReviewEngine timedOut = new DeterministicReviewEngine(
                (pattern, sensitive, value) -> {
                    throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR, "timeout");
                }, new ReviewEvidenceFactory());
        assertThatThrownBy(() -> timedOut.evaluateFull(namedInput(
                new FileNameRule(FileNameRuleKind.REGEX, ".*\\.pdf", true), "safe.pdf")))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR);
    }

    @Test
    void outOfScopeContentCannotCreateFindingsAndHandlersOnlyOccupyM002SpiSlots() {
        assertThat(findingsFor(
                none(), "typo-missing-field-ineligible-unsigned-date-inconsistent-proof-writing.pdf"))
                .isEmpty();
        assertThat(com.javaee.aiservice.review.m0.job.ReviewM0JobHandler.class)
                .isAssignableFrom(FormalReviewJobHandler.class)
                .isAssignableFrom(IncrementalReviewJobHandler.class);

        List<Class<?>> implementationTypes = List.of(
                DeterministicReviewEngine.class,
                ReviewM0ReviewJobExecutor.class,
                FormalReviewJobHandler.class,
                IncrementalReviewJobHandler.class);
        implementationTypes.stream()
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredFields()))
                .map(field -> field.getType().getName())
                .forEach(typeName -> assertThat(typeName)
                        .doesNotContainIgnoringCase("Agent")
                        .doesNotContainIgnoringCase("RemoteConsent")
                        .doesNotContainIgnoringCase("Model"));
    }

    @Test
    void permutationsAndRepeatedExecutionProduceTheSameCanonicalResult() {
        FormalChecklist checklist = checklist(3, none(), false);
        List<ReviewInputManifest.MaterialFact> materials = new ArrayList<>(List.of(
                available("material-b", "b.pdf"),
                available("material-a", "a.pdf"),
                material("material-c", "c.pdf", MaterialStatus.CANNOT_CONFIRM,
                        CannotConfirmReason.ENCRYPTED)));
        List<ReviewInputManifest.AssignmentFact> assignments = new ArrayList<>(List.of(
                assigned("material-b"), assigned("material-a")));
        List<ReviewInputManifest.DiagnosticFact> diagnostics = new ArrayList<>(List.of(
                new ReviewInputManifest.DiagnosticFact(
                        "diagnostic-1", DiagnosticSubjectType.MATERIAL, "material-c",
                        CannotConfirmReason.ENCRYPTED, "DECRYPT", DIGEST_C)));
        ReviewInputManifest canonical = manifest(
                materials, assignments, diagnostics, 3, 2, 1, "upload-order");
        String expectedJson = canonical.canonicalJson();
        List<String> expectedFindings = normalized(engine.evaluateFull(captured(checklist, canonical)));
        Random random = new Random(305L);

        for (int run = 0; run < 50; run++) {
            Collections.shuffle(materials, random);
            Collections.shuffle(assignments, random);
            Collections.shuffle(diagnostics, random);
            ReviewInputManifest shuffled = manifest(
                    materials, assignments, diagnostics, 3, 2, 1, "upload-order");
            assertThat(shuffled.canonicalJson()).isEqualTo(expectedJson);
            assertThat(normalized(engine.evaluateFull(captured(checklist, shuffled))))
                    .isEqualTo(expectedFindings);
        }
    }

    @Test
    void assignmentOrMaterialStateChangeExpandsCompletenessAndMatchesFullReview() {
        FormalChecklist checklist = checklist(2, none(), false);
        ReviewInputManifest base = manifest(
                List.of(available("material-1", "one.pdf"), available("material-2", "two.pdf")),
                List.of(assigned("material-1"), assigned("material-2")), List.of(),
                1, 1, 0, "upload-1");
        ReviewInputManifest assignmentChanged = manifest(
                base.materials(), List.of(assigned("material-1")), List.of(),
                1, 2, 0, "upload-1");
        assertIncrementallyEquivalent(checklist, base, assignmentChanged, List.of("material-2"));

        ReviewInputManifest stateChanged = manifest(
                List.of(available("material-1", "one.pdf"),
                        material("material-2", "two.pdf", MaterialStatus.CANNOT_CONFIRM,
                                CannotConfirmReason.CORRUPTED)),
                base.assignments(), List.of(),
                2, 1, 0, "upload-2");
        assertIncrementallyEquivalent(checklist, base, stateChanged, List.of("material-2"));
    }

    @Test
    void incrementalBaseFingerprintAndDeclaredChangesNeverSilentlyFallBack() {
        ReviewInputManifest base = manifest(
                List.of(available("material-1", "one.pdf")),
                List.of(assigned("material-1")), List.of(), 1, 1, 1, "upload-1");
        ReviewInputManifest renamed = manifest(
                List.of(available("material-1", "renamed.pdf")),
                base.assignments(), List.of(), 1, 1, 2, "upload-1");

        assertThatThrownBy(() -> incremental.validate(base, renamed, List.of("material-2")))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_INCREMENTAL_BASE_MISMATCH);
        ReviewInputManifest otherEngine = new ReviewInputManifest(
                base.contractVersion(), "different-engine", base.caseId(), base.basisRevision(), base.bases(),
                base.checklistSnapshotId(), base.checklistSnapshotDigest(), base.materialSetRevision(),
                base.assignmentRevision(), base.nameRevision(), base.uploadConfirmationId(),
                base.uploadMaterialSetDigest(), base.sources(), base.materials(), base.assignments(),
                base.diagnostics());
        assertThatThrownBy(() -> incremental.validate(base, otherEngine, List.of("material-1")))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_INCREMENTAL_BASE_MISMATCH);
    }

    @Test
    void nameOnlyRenameOfCannotConfirmMaterialMustRefreshEvidenceAndMatchFullReview() {
        FormalChecklist checklist = checklist(1, none(), false);
        ReviewInputManifest base = manifest(
                List.of(material("material-1", "before.pdf", MaterialStatus.CANNOT_CONFIRM,
                        CannotConfirmReason.ENCRYPTED)),
                List.of(), List.of(), 1, 0, 1, "upload-1");
        ReviewInputManifest renamed = manifest(
                List.of(material("material-1", "after.pdf", MaterialStatus.CANNOT_CONFIRM,
                        CannotConfirmReason.ENCRYPTED)),
                List.of(), List.of(), 1, 0, 2, "upload-1");
        IncrementalPlan plan = incremental.validate(base, renamed, List.of("material-1"));

        List<FindingDraft> incrementalFindings = engine.evaluateIncremental(
                captured(checklist, renamed), engine.evaluateFull(captured(checklist, base)), plan);
        List<FindingDraft> fullFindings = engine.evaluateFull(captured(checklist, renamed));

        assertThat(plan.nameOnly()).isTrue();
        assertThat(normalized(incrementalFindings)).isEqualTo(normalized(fullFindings));
    }

    @Test
    void nameOnlyExtensionChangeMustNotLoseTypeMismatchComparedWithFullReview() {
        FormalChecklist checklist = checklist(1, none(), false);
        ReviewInputManifest base = manifest(
                List.of(available("material-1", "before.pdf")),
                List.of(assigned("material-1")), List.of(), 1, 1, 1, "upload-1");
        ReviewInputManifest renamed = manifest(
                List.of(available("material-1", "after.docx")),
                base.assignments(), List.of(), 1, 1, 2, "upload-1");
        IncrementalPlan plan = incremental.validate(base, renamed, List.of("material-1"));

        List<FindingDraft> incrementalFindings = engine.evaluateIncremental(
                captured(checklist, renamed), engine.evaluateFull(captured(checklist, base)), plan);
        List<FindingDraft> fullFindings = engine.evaluateFull(captured(checklist, renamed));

        assertThat(plan.nameOnly()).isTrue();
        assertThat(normalized(incrementalFindings)).isEqualTo(normalized(fullFindings));
        assertThat(fullFindings).extracting(FindingDraft::cannotConfirmReason)
                .containsExactly(CannotConfirmReason.TYPE_MISMATCH);
    }

    private void assertIncrementallyEquivalent(
            FormalChecklist checklist,
            ReviewInputManifest base,
            ReviewInputManifest current,
            List<String> changedMaterialIds
    ) {
        IncrementalPlan plan = incremental.validate(base, current, changedMaterialIds);
        List<FindingDraft> incrementalFindings = engine.evaluateIncremental(
                captured(checklist, current), engine.evaluateFull(captured(checklist, base)), plan);
        assertThat(normalized(incrementalFindings))
                .isEqualTo(normalized(engine.evaluateFull(captured(checklist, current))));
    }

    private List<FindingDraft> findingsFor(FileNameRule rule, String name) {
        return engine.evaluateFull(namedInput(rule, name));
    }

    private CapturedReviewInput namedInput(FileNameRule rule, String name) {
        FormalChecklist checklist = checklist(1, rule, false);
        return captured(checklist, manifest(
                List.of(available("material-1", name)),
                List.of(assigned("material-1")), List.of(), 1, 1, 1, "upload-name"));
    }

    private static FormalChecklist checklist(int minimumCount, FileNameRule rule, boolean official) {
        List<FormalChecklist.SourceRef> sources = official
                ? List.of(new FormalChecklist.SourceRef(
                        "basis-1", new OfficialBasisSourceLocator(
                        null, 1, null, null, "block-1", null)))
                : List.of();
        return new FormalChecklist(
                "snapshot-1", "checklist-revision-1", 1, 7, DIGEST_A,
                List.of(new FormalChecklist.Item(
                        "requirement-1", RequirementApplicability.APPLIES, minimumCount, rule,
                        official ? RequirementOrigin.OFFICIAL_EXTRACTED : RequirementOrigin.USER_ADDED,
                        official ? RequirementAuthorship.EXTRACTED_TEXT : RequirementAuthorship.USER_REWRITTEN,
                        3, "checklist-revision-1:requirement-1:3", sources)));
    }

    private static FileNameRule none() {
        return new FileNameRule(FileNameRuleKind.NONE, null, null);
    }

    private static CapturedReviewInput captured(
            FormalChecklist checklist, ReviewInputManifest manifest
    ) {
        return new CapturedReviewInput(
                checklist,
                new MaterialCollectionSnapshot(
                        manifest.caseId(), manifest.materialSetRevision(), manifest.assignmentRevision(),
                        manifest.nameRevision(), List.of(), List.of(), List.of(), List.of(), null),
                manifest);
    }

    private static ReviewInputManifest manifest(
            List<ReviewInputManifest.MaterialFact> materials,
            List<ReviewInputManifest.AssignmentFact> assignments,
            List<ReviewInputManifest.DiagnosticFact> diagnostics,
            long materialRevision,
            long assignmentRevision,
            long nameRevision,
            String uploadId
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
                materialRevision,
                assignmentRevision,
                nameRevision,
                uploadId,
                DIGEST_C,
                List.of(new ReviewInputManifest.SourceFact(
                        "source-1", FormalFileType.PDF, DIGEST_B, 100)),
                materials,
                assignments,
                diagnostics);
    }

    private static ReviewInputManifest.MaterialFact available(String materialId, String name) {
        return material(materialId, name, MaterialStatus.AVAILABLE, null);
    }

    private static ReviewInputManifest.MaterialFact material(
            String materialId,
            String name,
            MaterialStatus status,
            CannotConfirmReason reason
    ) {
        return new ReviewInputManifest.MaterialFact(
                materialId, "source-1", MaterialKind.TOP_LEVEL_FILE, null, null,
                DIGEST_A, FormalFileType.PDF, name, status, reason,
                reason == null ? null : "SYNTHETIC_STAGE");
    }

    private static ReviewInputManifest.AssignmentFact assigned(String materialId) {
        return new ReviewInputManifest.AssignmentFact(
                materialId, "requirement-1", AssignmentStatus.USER_CONFIRMED);
    }

    private static List<String> normalized(List<FindingDraft> findings) {
        return findings.stream().map(FindingDraft::normalizedKey).sorted().toList();
    }

    private static void assertDualEvidence(FindingDraft finding) {
        assertThat(finding.evidence()).extracting(EvidenceDraft::side)
                .contains(EvidenceSide.RULE, EvidenceSide.MATERIAL);
    }
}
