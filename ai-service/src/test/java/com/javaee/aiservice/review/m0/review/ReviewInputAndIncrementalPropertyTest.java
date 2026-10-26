package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewInputAndIncrementalPropertyTest {
    private final IncrementalReviewValidator validator = new IncrementalReviewValidator();

    @Test
    void inputFingerprintAndCanonicalJobRequestIgnoreEveryInputOrdering() {
        ReviewInputManifest original = manifest(
                materials("material-b", "b.pdf", "material-a", "a.pdf"),
                assignments("material-b", "material-a"), 4, 8, 2, "upload-1");
        String expectedFingerprint = original.fingerprint().sha256();
        String expectedJson = original.canonicalJson();
        Random random = new Random(52105L);

        for (int iteration = 0; iteration < 100; iteration++) {
            List<ReviewInputManifest.MaterialFact> materials = new ArrayList<>(original.materials());
            List<ReviewInputManifest.AssignmentFact> assignments = new ArrayList<>(original.assignments());
            Collections.shuffle(materials, random);
            Collections.shuffle(assignments, random);
            ReviewInputManifest shuffled = manifest(materials, assignments, 4, 8, 2, "upload-1");
            assertThat(shuffled.fingerprint().sha256()).isEqualTo(expectedFingerprint);
            assertThat(shuffled.canonicalJson()).isEqualTo(expectedJson);
            assertThat(ReviewInputManifest.readCanonical(shuffled.canonicalJson())).isEqualTo(original);
        }
    }

    @Test
    void manifestOrderingUsesUnicodeCodePointsRatherThanUtf16CodeUnits() {
        String privateUseBmp = "\ue000";
        String supplementary = "\ud83d\ude00";
        ReviewInputManifest manifest = manifest(
                materials(supplementary, "emoji.pdf", privateUseBmp, "bmp.pdf"),
                assignments(supplementary, privateUseBmp), 4, 8, 2, "upload-1");
        ReviewInputManifest reversed = manifest(
                materials(privateUseBmp, "bmp.pdf", supplementary, "emoji.pdf"),
                assignments(privateUseBmp, supplementary), 4, 8, 2, "upload-1");

        assertThat(manifest.materials()).extracting(ReviewInputManifest.MaterialFact::materialId)
                .containsExactly(privateUseBmp, supplementary);
        assertThat(reversed.canonicalJson()).isEqualTo(manifest.canonicalJson());
        assertThat(reversed.fingerprint()).isEqualTo(manifest.fingerprint());
    }

    @Test
    void repeatedFullReviewIsCanonicalAndDeterministic() {
        CapturedReviewInput input = captured(
                M005Fixtures.checklist(3, M005Fixtures.none()),
                manifest(materials("material-b", "b.pdf", "material-a", "a.pdf"),
                        assignments("material-a"), 1, 1, 0, "upload-1"));
        DeterministicReviewEngine engine = engine(value -> { });

        List<String> first = normalized(engine.evaluateFull(input));
        for (int run = 0; run < 25; run++) {
            assertThat(normalized(engine.evaluateFull(input))).isEqualTo(first);
        }
    }

    @Test
    void singleLogicalRenameRecomputesOnlyThatMaterialsNamingAndEqualsFullReview() {
        FormalChecklist checklist = M005Fixtures.checklist(
                2, new FileNameRule(FileNameRuleKind.REGEX, "input-[0-9]\\.pdf", true));
        ReviewInputManifest baseManifest = manifest(
                materials("material-1", "input-1.pdf", "material-2", "input-2.pdf"),
                assignments("material-1", "material-2"), 1, 1, 1, "upload-1");
        ReviewInputManifest currentManifest = manifest(
                materials("material-1", "other.pdf", "material-2", "input-2.pdf"),
                assignments("material-1", "material-2"), 1, 1, 2, "upload-1");
        CapturedReviewInput base = captured(checklist, baseManifest);
        CapturedReviewInput current = captured(checklist, currentManifest);
        DeterministicReviewEngine baseEngine = engine(value -> { });
        List<FindingDraft> baseFindings = baseEngine.evaluateFull(base);
        List<String> evaluatedNames = new ArrayList<>();
        DeterministicReviewEngine incrementalEngine = engine(evaluatedNames::add);

        IncrementalPlan plan = validator.validate(baseManifest, currentManifest, List.of("material-1"));
        List<FindingDraft> incremental = incrementalEngine.evaluateIncremental(current, baseFindings, plan);
        List<FindingDraft> full = engine(value -> { }).evaluateFull(current);

        assertThat(plan.nameOnly()).isTrue();
        assertThat(evaluatedNames).containsExactly("other.pdf");
        assertThat(normalized(incremental)).isEqualTo(normalized(full));
    }

    @Test
    void assignmentChangeExpandsCompletenessAndStillEqualsFullReview() {
        FormalChecklist checklist = M005Fixtures.checklist(2, M005Fixtures.none());
        ReviewInputManifest baseManifest = manifest(
                materials("material-1", "one.pdf", "material-2", "two.pdf"),
                assignments("material-1", "material-2"), 1, 1, 0, "upload-1");
        ReviewInputManifest currentManifest = manifest(
                materials("material-1", "one.pdf", "material-2", "two.pdf"),
                assignments("material-1"), 1, 2, 0, "upload-1");
        CapturedReviewInput base = captured(checklist, baseManifest);
        CapturedReviewInput current = captured(checklist, currentManifest);
        DeterministicReviewEngine reviewEngine = engine(value -> { });
        IncrementalPlan plan = validator.validate(baseManifest, currentManifest, List.of("material-2"));

        List<FindingDraft> incremental = reviewEngine.evaluateIncremental(
                current, reviewEngine.evaluateFull(base), plan);
        List<FindingDraft> full = reviewEngine.evaluateFull(current);

        assertThat(plan.nameOnly()).isFalse();
        assertThat(normalized(incremental)).isEqualTo(normalized(full));
    }

    @Test
    void undeclaredOrOverdeclaredChangesFailWithoutPartialFallback() {
        ReviewInputManifest base = manifest(
                materials("material-1", "one.pdf"), assignments("material-1"), 1, 1, 1, "upload-1");
        ReviewInputManifest current = manifest(
                materials("material-1", "renamed.pdf"), assignments("material-1"), 1, 1, 2, "upload-1");

        assertThatThrownBy(() -> validator.validate(base, current, List.of("material-2")))
                .isInstanceOf(com.javaee.aiservice.review.m0.model.ReviewM0Exception.class)
                .hasMessageContaining("exactly describe");
        assertThatThrownBy(() -> validator.validate(
                base, current, List.of("material-1", "material-2")))
                .isInstanceOf(com.javaee.aiservice.review.m0.model.ReviewM0Exception.class);
    }

    private static DeterministicReviewEngine engine(java.util.function.Consumer<String> observed) {
        return new DeterministicReviewEngine((pattern, caseSensitive, value) -> {
            observed.accept(value);
            int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE
                    | java.util.regex.Pattern.UNICODE_CASE;
            return java.util.regex.Pattern.compile(pattern, flags).matcher(value).matches();
        }, new ReviewEvidenceFactory());
    }

    private static CapturedReviewInput captured(FormalChecklist checklist, ReviewInputManifest manifest) {
        return new CapturedReviewInput(
                checklist,
                new com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot(
                        "case-1", manifest.materialSetRevision(), manifest.assignmentRevision(),
                        manifest.nameRevision(), List.of(), List.of(), List.of(), List.of(), null),
                manifest);
    }

    private static ReviewInputManifest manifest(
            List<ReviewInputManifest.MaterialFact> materials,
            List<ReviewInputManifest.AssignmentFact> assignments,
            long materialRevision,
            long assignmentRevision,
            long nameRevision,
            String uploadId
    ) {
        return new ReviewInputManifest(
                FormalChecklist.CONTRACT_VERSION, DeterministicReviewEngine.ENGINE_VERSION, "case-1", 1,
                List.of(new ReviewInputManifest.BasisFact(
                        "basis-1", com.javaee.aiservice.review.m0.model.OfficialBasisRole.APPLICATION_NOTICE,
                        M005Fixtures.DIGEST_B)),
                "clsnap-1", M005Fixtures.DIGEST_A,
                materialRevision, assignmentRevision, nameRevision, uploadId, M005Fixtures.DIGEST_C,
                List.of(new ReviewInputManifest.SourceFact(
                        "source-1", FormalFileType.PDF, M005Fixtures.DIGEST_B, 100)),
                materials, assignments, List.of());
    }

    private static List<ReviewInputManifest.MaterialFact> materials(String... idAndName) {
        List<ReviewInputManifest.MaterialFact> values = new ArrayList<>();
        for (int index = 0; index < idAndName.length; index += 2) {
            values.add(new ReviewInputManifest.MaterialFact(
                    idAndName[index], "source-1", MaterialKind.TOP_LEVEL_FILE, null, null,
                    M005Fixtures.DIGEST_A, FormalFileType.PDF, idAndName[index + 1],
                    MaterialStatus.AVAILABLE, null, null));
        }
        return values;
    }

    private static List<ReviewInputManifest.AssignmentFact> assignments(String... materialIds) {
        return java.util.Arrays.stream(materialIds)
                .map(id -> new ReviewInputManifest.AssignmentFact(
                        id, "requirement-1", AssignmentStatus.USER_CONFIRMED))
                .toList();
    }

    private static List<String> normalized(List<FindingDraft> findings) {
        return findings.stream().map(FindingDraft::normalizedKey).sorted().toList();
    }
}
