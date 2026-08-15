package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.EvidenceSide;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicReviewEngineTest {
    private final DeterministicReviewEngine engine = new DeterministicReviewEngine(
            new BoundedRegexMatcher(Duration.ofMillis(250)), new ReviewEvidenceFactory());

    @Test
    void insufficientConfirmedAssignmentsProduceExactlyOneEvidenceCompleteMissing() {
        CapturedReviewInput input = M005Fixtures.empty(M005Fixtures.checklist(2, M005Fixtures.none()));

        List<FindingDraft> findings = engine.evaluateFull(input);

        assertThat(findings).hasSize(1);
        FindingDraft missing = findings.get(0);
        assertThat(missing.type()).isEqualTo(FindingType.MISSING);
        assertThat(missing.evidence()).extracting(item -> item.side())
                .contains(EvidenceSide.RULE, EvidenceSide.MATERIAL);
        assertThat(missing.evidence()).anySatisfy(item -> {
            if (item.side() == EvidenceSide.RULE) {
                assertThat(item.refJson()).contains("checklistSnapshotId", "minimumCount", "ruleRevision");
            }
        });
        assertThat(missing.evidence()).anySatisfy(item -> {
            if (item.side() == EvidenceSide.MATERIAL) {
                assertThat(item.refJson()).contains("uploadConfirmationId", "confirmedAssignments", "materials");
            }
        });
    }

    @ParameterizedTest
    @EnumSource(CannotConfirmReason.class)
    void everyFrozenCannotConfirmReasonSuppressesAllMissing(CannotConfirmReason reason) {
        CapturedReviewInput input = M005Fixtures.input(
                M005Fixtures.checklist(2, M005Fixtures.none()),
                List.of(M005Fixtures.cannot("material-1", reason)),
                List.of(), List.of(), 1, 0, 0, "upload-1");

        List<FindingDraft> findings = engine.evaluateFull(input);

        assertThat(findings).allMatch(item -> item.type() == FindingType.CANNOT_CONFIRM);
        assertThat(findings).extracting(FindingDraft::cannotConfirmReason).containsExactly(reason);
        assertThat(findings).noneMatch(item -> item.type() == FindingType.MISSING);
    }

    @Test
    void reachingMinimumCountProducesNoMissing() {
        CapturedReviewInput input = M005Fixtures.input(
                M005Fixtures.checklist(1, M005Fixtures.none()),
                List.of(M005Fixtures.available("material-1", "input.pdf")),
                List.of(M005Fixtures.assigned("material-1")), List.of(), 1, 1, 0, "upload-1");

        assertThat(engine.evaluateFull(input)).isEmpty();
    }

    @Test
    void exactRulesRespectExplicitCaseSensitivity() {
        CapturedReviewInput sensitive = namedInput(
                new FileNameRule(FileNameRuleKind.EXACT, "Form.pdf", true), "form.pdf");
        CapturedReviewInput insensitive = namedInput(
                new FileNameRule(FileNameRuleKind.EXACT, "Form.pdf", false), "form.pdf");

        assertThat(engine.evaluateFull(sensitive)).extracting(FindingDraft::type)
                .containsExactly(FindingType.FILE_NAME_NONCOMPLIANT);
        assertThat(engine.evaluateFull(insensitive)).isEmpty();
    }

    @Test
    void regexRulesUseFullMatchAndExplicitCaseSensitivity() {
        CapturedReviewInput match = namedInput(
                new FileNameRule(FileNameRuleKind.REGEX, "FORM-[0-9]+\\.PDF", false), "form-12.pdf");
        CapturedReviewInput noFullMatch = namedInput(
                new FileNameRule(FileNameRuleKind.REGEX, "[0-9]+", false), "form-12.pdf");

        assertThat(engine.evaluateFull(match)).isEmpty();
        assertThat(engine.evaluateFull(noFullMatch)).extracting(FindingDraft::type)
                .containsExactly(FindingType.FILE_NAME_NONCOMPLIANT);
    }

    @Test
    void noneStillRunsGlobalSafetyAndFormalExtensionIsCaseInsensitive() {
        CapturedReviewInput upperExtension = namedInput(M005Fixtures.none(), "safe.PDF");
        CapturedReviewInput unsafe = namedInput(M005Fixtures.none(), "../unsafe.pdf");

        assertThat(engine.evaluateFull(upperExtension)).isEmpty();
        assertThat(engine.evaluateFull(unsafe)).extracting(FindingDraft::type)
                .containsExactly(FindingType.FILE_NAME_NONCOMPLIANT);
    }

    @Test
    void actualTypeMismatchIsCannotConfirmAndNeverAFileNameFinding() {
        CapturedReviewInput input = namedInput(M005Fixtures.none(), "input.docx");

        List<FindingDraft> findings = engine.evaluateFull(input);

        assertThat(findings).extracting(FindingDraft::type).containsExactly(FindingType.CANNOT_CONFIRM);
        assertThat(findings.get(0).cannotConfirmReason()).isEqualTo(CannotConfirmReason.TYPE_MISMATCH);
    }

    @Test
    void regexTimeoutOrInternalFailureIsNeverPackagedAsNoncompliance() {
        DeterministicReviewEngine failing = new DeterministicReviewEngine(
                (pattern, sensitive, value) -> {
                    throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR, "timeout");
                }, new ReviewEvidenceFactory());
        CapturedReviewInput input = namedInput(
                new FileNameRule(FileNameRuleKind.REGEX, ".*\\.pdf", true), "input.pdf");

        assertThatThrownBy(() -> failing.evaluateFull(input))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR);
    }

    @Test
    void scopeExcludedContentCategoriesCannotCreateFindings() {
        CapturedReviewInput input = namedInput(M005Fixtures.none(), "typo-unsigned-ineligible-inconsistent.pdf");

        assertThat(engine.evaluateFull(input)).isEmpty();
    }

    private CapturedReviewInput namedInput(FileNameRule rule, String name) {
        return M005Fixtures.input(
                M005Fixtures.checklist(1, rule),
                List.of(M005Fixtures.available("material-1", name)),
                List.of(M005Fixtures.assigned("material-1")), List.of(), 1, 1, 1, "upload-1");
    }
}
