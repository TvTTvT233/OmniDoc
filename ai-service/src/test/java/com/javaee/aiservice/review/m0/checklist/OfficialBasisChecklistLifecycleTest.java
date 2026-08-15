package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.OfficialBasis;
import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.model.RequirementOrigin;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialBasisChecklistLifecycleTest {
    @Test
    void officialExtractionUserRewriteUserAdditionAndDisableKeepImmutableLineage() {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase("case-lineage", "owner", ProcessingMode.LOCAL_ONLY);
        fixture.attachReadyBasis("owner", "case-lineage", 0, "basis-1");
        ChecklistDraftView extracted = fixture.checklistService().replaceOfficialCandidatesOwned(
                "owner", "case-lineage", 1, null,
                List.of(fixture.officialCandidate("basis-1", "Extracted requirement")));
        ChecklistDraftItem original = extracted.items().get(0);

        ChecklistDraftView rewritten = fixture.checklistService().editRequirementOwned(
                "owner", "case-lineage", original.requirementId(),
                new ChecklistItemEdit(
                        0, "User rewritten requirement", true, RequirementApplicability.APPLIES,
                        1, new FileNameRule(FileNameRuleKind.EXACT, "application.pdf", false)));
        ChecklistDraftItem rewrite = rewritten.items().get(0);
        ChecklistDraftView userAdded = fixture.checklistService().addUserRequirementOwned(
                "owner", "case-lineage", "User-added cover", true,
                RequirementApplicability.APPLIES, 1,
                new FileNameRule(FileNameRuleKind.NONE, null, null));
        ChecklistDraftView disabled = fixture.checklistService().editRequirementOwned(
                "owner", "case-lineage", original.requirementId(),
                new ChecklistItemEdit(
                        rewrite.itemVersion(), rewrite.displayName(), false, rewrite.applicability(),
                        rewrite.minimumCount(), rewrite.fileNameRule()));

        assertThat(original.origin()).isEqualTo(RequirementOrigin.OFFICIAL_EXTRACTED);
        assertThat(original.authorship()).isEqualTo(RequirementAuthorship.EXTRACTED_TEXT);
        assertThat(rewrite.origin()).isEqualTo(RequirementOrigin.OFFICIAL_EXTRACTED);
        assertThat(rewrite.authorship()).isEqualTo(RequirementAuthorship.USER_REWRITTEN);
        assertThat(rewrite.sourceRefs()).isEqualTo(original.sourceRefs());
        assertThat(userAdded.items()).filteredOn(item -> item.origin() == RequirementOrigin.USER_ADDED)
                .singleElement().satisfies(item -> {
                    assertThat(item.authorship()).isEqualTo(RequirementAuthorship.USER_REWRITTEN);
                    assertThat(item.sourceRefs()).isEmpty();
                });
        assertThat(disabled.items()).filteredOn(item -> item.requirementId().equals(original.requirementId()))
                .singleElement().extracting(ChecklistDraftItem::enabled).isEqualTo(false);
        assertThat(fixture.checklistStore().findRevisionByIdOwned(
                "owner", "case-lineage", extracted.checklistRevisionId()).orElseThrow().items())
                .containsExactly(original);

        ReviewCase beforePublish = fixture.caseStore().findOwned("owner", "case-lineage").orElseThrow();
        String userRequirementId = disabled.items().stream()
                .filter(item -> item.origin() == RequirementOrigin.USER_ADDED)
                .findFirst().orElseThrow().requirementId();
        ChecklistSnapshot snapshot = fixture.checklistService().publishOwned(
                "owner", "case-lineage", beforePublish.version(), disabled.revision(),
                Set.of(userRequirementId));

        assertThat(snapshot.snapshotJson()).contains(userRequirementId)
                .doesNotContain(original.requirementId());
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_requirement_revision WHERE requirement_id = ?",
                Integer.class, original.requirementId())).isEqualTo(4);
    }

    @Test
    void manualBasisReclassificationMakesPublishedSnapshotStaleAndUnreviewable() {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase("case-stale", "owner", ProcessingMode.LOCAL_ONLY);
        OfficialBasis basis = fixture.attachReadyBasis("owner", "case-stale", 0, "basis-stale");
        ChecklistDraftView draft = fixture.checklistService().replaceOfficialCandidatesOwned(
                "owner", "case-stale", 1, null,
                List.of(fixture.officialCandidate("basis-stale", "Required form")));
        ReviewCase beforePublish = fixture.caseStore().findOwned("owner", "case-stale").orElseThrow();
        ChecklistSnapshot snapshot = fixture.checklistService().publishOwned(
                "owner", "case-stale", beforePublish.version(), draft.revision(),
                Set.of(draft.items().get(0).requirementId()));
        ReviewCase afterPublish = fixture.caseStore().findOwned("owner", "case-stale").orElseThrow();

        fixture.basisService().reclassifyOwned(
                "owner", "case-stale", basis.basisId(), afterPublish.version(), basis.version(),
                OfficialBasisRole.FILLING_GUIDE);

        ChecklistSnapshot stored = fixture.caseStore().findChecklistSnapshotOwned(
                "owner", "case-stale", snapshot.checklistSnapshotId()).orElseThrow();
        assertThat(stored.lifecycle()).isEqualTo(ChecklistLifecycle.STALE_BASIS);
        assertThatThrownBy(() -> fixture.checklistService().requireCurrentFormalSnapshotOwned(
                "owner", "case-stale", snapshot.checklistSnapshotId()))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_STALE_BASIS);
    }

    @Test
    void candidateDraftCannotBeUsedAsFormalReviewInputAndSnapshotsStayImmutable() {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase("case-immutable", "owner", ProcessingMode.LOCAL_ONLY);
        fixture.attachReadyBasis("owner", "case-immutable", 0, "basis-immutable");
        ChecklistDraftView draft = fixture.checklistService().replaceOfficialCandidatesOwned(
                "owner", "case-immutable", 1, null,
                List.of(fixture.officialCandidate("basis-immutable", "Required form")));

        assertThatThrownBy(() -> fixture.checklistService().requireCurrentFormalSnapshotOwned(
                "owner", "case-immutable", draft.checklistRevisionId()))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED);

        ReviewCase current = fixture.caseStore().findOwned("owner", "case-immutable").orElseThrow();
        ChecklistSnapshot snapshot = fixture.checklistService().publishOwned(
                "owner", "case-immutable", current.version(), draft.revision(),
                Set.of(draft.items().get(0).requirementId()));
        String originalJson = snapshot.snapshotJson();
        fixture.checklistService().editRequirementOwned(
                "owner", "case-immutable", draft.items().get(0).requirementId(),
                new ChecklistItemEdit(
                        0, "Later user revision", true, RequirementApplicability.APPLIES, 1,
                        new FileNameRule(FileNameRuleKind.NONE, null, null)));

        assertThat(fixture.caseStore().findChecklistSnapshotOwned(
                "owner", "case-immutable", snapshot.checklistSnapshotId()).orElseThrow().snapshotJson())
                .isEqualTo(originalJson).doesNotContain("Later user revision");
    }

    @Test
    void unreliableOfficialSourceNeverEntersCandidateOrPublishableChecklist() {
        assertThatThrownBy(() -> new ChecklistSourceRef(
                "basis", new OfficialBasisSourceLocator(null, null, null, null, null, null), "excerpt"))
                .isInstanceOf(ReviewM0Exception.class);
        assertThatThrownBy(() -> new ChecklistSourceRef(
                "basis", new OfficialBasisSourceLocator(null, 1, null, null, null, null), " "))
                .isInstanceOf(ReviewM0Exception.class);

        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase("case-source", "owner", ProcessingMode.LOCAL_ONLY);
        fixture.attachReadyBasis("owner", "case-source", 0, "basis-source");
        assertThatThrownBy(() -> fixture.checklistService().replaceOfficialCandidatesOwned(
                "owner", "case-source", 1, null,
                List.of(new ChecklistCandidate(
                        "Unlocated model text", true, RequirementApplicability.APPLIES, 1,
                        new FileNameRule(FileNameRuleKind.NONE, null, null), List.of()))))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID);
        assertThat(fixture.checklistStore().findLatestDraftOwned("owner", "case-source")).isEmpty();
    }

    @Test
    void unacknowledgedConditionalAndUnsafeRegexRulesCannotPublish() {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase("case-gates", "owner", ProcessingMode.LOCAL_ONLY);
        fixture.attachReadyBasis("owner", "case-gates", 0, "basis-gates");
        ChecklistDraftView draft = fixture.checklistService().replaceOfficialCandidatesOwned(
                "owner", "case-gates", 1, null,
                List.of(fixture.officialCandidate("basis-gates", "Required form")));
        ReviewCase current = fixture.caseStore().findOwned("owner", "case-gates").orElseThrow();

        assertThatThrownBy(() -> fixture.checklistService().publishOwned(
                "owner", "case-gates", current.version(), draft.revision(), Set.of()))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_ACK_MISMATCH);

        ChecklistDraftView conditional = fixture.checklistService().editRequirementOwned(
                "owner", "case-gates", draft.items().get(0).requirementId(),
                new ChecklistItemEdit(
                        0, "Conditional form", true, RequirementApplicability.NEEDS_USER_DECISION,
                        1, new FileNameRule(FileNameRuleKind.NONE, null, null)));
        assertThatThrownBy(() -> fixture.checklistService().publishOwned(
                "owner", "case-gates", current.version(), conditional.revision(),
                Set.of(conditional.items().get(0).requirementId())))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CONDITIONAL_UNRESOLVED);

        ChecklistDraftView unsafe = fixture.checklistService().editRequirementOwned(
                "owner", "case-gates", conditional.items().get(0).requirementId(),
                new ChecklistItemEdit(
                        1, "Regex form", true, RequirementApplicability.APPLIES, 1,
                        new FileNameRule(FileNameRuleKind.REGEX, "(a+)+\\.pdf", true)));
        assertThatThrownBy(() -> fixture.checklistService().publishOwned(
                "owner", "case-gates", current.version(), unsafe.revision(),
                Set.of(unsafe.items().get(0).requirementId())))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID);
    }

    @Test
    void invalidOrIncompleteFileNameRulesAreRejectedBeforePublication() {
        assertThatThrownBy(() -> new FileNameRule(FileNameRuleKind.EXACT, "form.pdf", null))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID);
        assertThatThrownBy(() -> new FileNameRule(FileNameRuleKind.REGEX, "([", true))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID);
        assertThatThrownBy(() -> RegexSafetyValidator.requirePublishable(
                new FileNameRule(FileNameRuleKind.REGEX, "a{999999999999999999999}", true)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID);
        assertThatThrownBy(() -> RegexSafetyValidator.requirePublishable(
                new FileNameRule(FileNameRuleKind.REGEX, "(a?)+\\.pdf", true)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID);
        RegexSafetyValidator.requirePublishable(
                new FileNameRule(FileNameRuleKind.REGEX, "^(?:form|notice)\\.pdf$", false));
        assertThatThrownBy(() -> new FileNameRule(FileNameRuleKind.NONE, null, false))
                .isInstanceOf(ReviewM0Exception.class);
    }

    @Test
    void cannotConfirmBasisAllowsOtherCandidatesButBlocksPublication() {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase("case-basis-block", "owner", ProcessingMode.LOCAL_ONLY);
        fixture.attachReadyBasis("owner", "case-basis-block", 0, "basis-ready");
        ReviewCase afterReady = fixture.caseStore().findOwned("owner", "case-basis-block").orElseThrow();
        OfficialBasis attached = fixture.basisService().attachOwned(
                "owner", "case-basis-block", afterReady.version(),
                new OfficialBasisAttachment(
                        "basis-bad", "file-bad", OfficialBasisRole.FILLING_GUIDE,
                        "bad.pdf", "application/pdf", 12, M003TestFixture.DIGEST_B, "v2"));
        fixture.basisService().markCannotConfirmOwned(
                "owner", "case-basis-block", "basis-bad", attached.version(),
                CannotConfirmReason.PARSE_FAILED, "LOCAL_PARSE");
        ReviewCase current = fixture.caseStore().findOwned("owner", "case-basis-block").orElseThrow();
        ChecklistDraftView draft = fixture.checklistService().replaceOfficialCandidatesOwned(
                "owner", "case-basis-block", current.basisRevision(), null,
                List.of(fixture.officialCandidate("basis-ready", "Reliable candidate")));

        assertThat(draft.items()).hasSize(1);
        assertThatThrownBy(() -> fixture.checklistService().publishOwned(
                "owner", "case-basis-block", current.version(), draft.revision(),
                Set.of(draft.items().get(0).requirementId())))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_BASIS_CANNOT_CONFIRM);
    }

    @Test
    void crossOwnerBasisAndChecklistReadsOrMutationsLookMissingWithoutSideEffects() {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase("case-owner", "owner", ProcessingMode.LOCAL_ONLY);
        fixture.attachReadyBasis("owner", "case-owner", 0, "basis-owner");
        ChecklistDraftView draft = fixture.checklistService().replaceOfficialCandidatesOwned(
                "owner", "case-owner", 1, null,
                List.of(fixture.officialCandidate("basis-owner", "Required")));
        int revisionCount = fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_checklist_revision", Integer.class);

        assertThat(fixture.checklistStore().findLatestDraftOwned("other", "case-owner")).isEmpty();
        assertThat(fixture.checklistStore().findBasisOwned("other", "case-owner", "basis-owner")).isEmpty();
        assertThatThrownBy(() -> fixture.checklistService().editRequirementOwned(
                "other", "case-owner", draft.items().get(0).requirementId(),
                new ChecklistItemEdit(
                        0, "Probe", false, RequirementApplicability.APPLIES, 1,
                        new FileNameRule(FileNameRuleKind.NONE, null, null))))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_checklist_revision", Integer.class))
                .isEqualTo(revisionCount);
    }
}
