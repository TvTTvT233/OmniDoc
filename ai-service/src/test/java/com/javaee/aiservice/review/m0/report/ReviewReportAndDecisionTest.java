package com.javaee.aiservice.review.m0.report;

import com.javaee.aiservice.review.m0.evidence.EvidenceDraft;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.EvidenceKind;
import com.javaee.aiservice.review.m0.model.EvidenceSide;
import com.javaee.aiservice.review.m0.model.FindingDecision;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import com.javaee.aiservice.review.m0.review.FindingDraft;
import com.javaee.aiservice.review.m0.store.JdbcReviewCaseStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewReportAndDecisionTest {
    @Test
    void immutableReportEvidenceAndIgnoreEventRemainReadableAndNeverBecomeReady() {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewJob leased = fixture.createAndLease(
                "job-report-1", ReviewJobType.FORMAL_REVIEW, fixture.fullEnvelope());
        FindingDraft cannot = cannotFinding();
        ReviewReportAssembler assembler = assembler();
        ReportBundle bundle = assembler.assemble(leased, fixture.fullEnvelope(), List.of(cannot));
        fixture.reports.publishOwned(
                M005ReportFixture.OWNER, M005ReportFixture.WORKER,
                M005ReportFixture.NOW.plusSeconds(2), bundle);
        FindingDecisionService decisions = new FindingDecisionService(
                fixture.database.jdbc(), fixture.database.transactions(), fixture.reader,
                Clock.fixed(M005ReportFixture.NOW.plusSeconds(3), ZoneOffset.UTC));
        String findingId = bundle.findings().get(0).findingId();

        FindingDecision first = decisions.ignoreOwned(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID,
                bundle.report().reportId(), findingId, "synthetic reason");
        FindingDecision replay = decisions.ignoreOwned(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID,
                bundle.report().reportId(), findingId, "different retry reason");
        EffectiveReportView view = decisions.getEffectiveOwned(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, bundle.report().reportId());

        assertThat(replay).isEqualTo(first);
        assertThat(view.ignoredCount()).isEqualTo(1);
        assertThat(view.effectiveAssessment()).isEqualTo(ReviewAssessment.M0_NOT_READY);
        assertThat(view.published().findings()).hasSize(1);
        assertThat(view.published().findings().get(0).evidence()).hasSize(2);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding WHERE report_id = ?", Integer.class,
                bundle.report().reportId())).isEqualTo(1);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_evidence_ref", Integer.class)).isEqualTo(2);
    }

    @Test
    void sameCaseAndInputFingerprintPublishesAtMostOneReportAcrossTwoJobs() {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewReportAssembler assembler = assembler();
        ReviewJob first = fixture.createAndLease(
                "job-report-a", ReviewJobType.FORMAL_REVIEW, fixture.fullEnvelope());
        ReportBundle firstBundle = assembler.assemble(first, fixture.fullEnvelope(), List.of(cannotFinding()));
        fixture.reports.publishOwned(
                M005ReportFixture.OWNER, M005ReportFixture.WORKER,
                M005ReportFixture.NOW.plusSeconds(2), firstBundle);
        ReviewJob second = fixture.createAndLease(
                "job-report-b", ReviewJobType.FORMAL_REVIEW, fixture.fullEnvelope());
        ReportBundle secondBundle = assembler.assemble(second, fixture.fullEnvelope(), List.of(cannotFinding()));
        ReviewJob completed = fixture.reports.publishOwned(
                M005ReportFixture.OWNER, M005ReportFixture.WORKER,
                M005ReportFixture.NOW.plusSeconds(3), secondBundle);

        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report WHERE case_id = ?", Integer.class,
                M005ReportFixture.CASE_ID)).isEqualTo(1);
        assertThat(completed.result().resultId()).isEqualTo(firstBundle.report().reportId());
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isEqualTo(1);
    }

    @Test
    void readyAssemblyRequiresEveryImmutableBlockingCountToBeZero() {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewJob leased = fixture.createAndLease(
                "job-ready", ReviewJobType.FORMAL_REVIEW, fixture.fullEnvelope());

        ReportBundle bundle = assembler().assemble(leased, fixture.fullEnvelope(), List.of());

        assertThat(bundle.report().assessment()).isEqualTo(ReviewAssessment.M0_READY);
        assertThat(bundle.report().missingCount()).isZero();
        assertThat(bundle.report().cannotConfirmCount()).isZero();
        assertThat(bundle.report().fileNameNoncompliantCount()).isZero();
    }

    @Test
    void reportFindingEvidenceAndIgnoreAreHiddenFromAnotherOwner() {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewJob leased = fixture.createAndLease(
                "job-owner-isolation", ReviewJobType.FORMAL_REVIEW, fixture.fullEnvelope());
        ReportBundle bundle = assembler().assemble(
                leased, fixture.fullEnvelope(), List.of(cannotFinding()));
        fixture.reports.publishOwned(
                M005ReportFixture.OWNER, M005ReportFixture.WORKER,
                M005ReportFixture.NOW.plusSeconds(2), bundle);
        FindingDecisionService decisions = new FindingDecisionService(
                fixture.database.jdbc(), fixture.database.transactions(), fixture.reader,
                Clock.fixed(M005ReportFixture.NOW.plusSeconds(3), ZoneOffset.UTC));

        assertThat(fixture.reader.findOwned(
                "different-owner", M005ReportFixture.CASE_ID, bundle.report().reportId())).isEmpty();
        assertThatThrownBy(() -> decisions.ignoreOwned(
                "different-owner", M005ReportFixture.CASE_ID, bundle.report().reportId(),
                bundle.findings().get(0).findingId(), null))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding_decision", Integer.class)).isZero();
    }

    @Test
    void differentEngineInvalidatesCurrentReportAndCannotReviveIt() {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewJob leased = fixture.createAndLease(
                "job-engine-freshness", ReviewJobType.FORMAL_REVIEW, fixture.fullEnvelope());
        ReportBundle bundle = assembler().assemble(leased, fixture.fullEnvelope(), List.of());
        fixture.reports.publishOwned(
                M005ReportFixture.OWNER, M005ReportFixture.WORKER,
                M005ReportFixture.NOW.plusSeconds(2), bundle);
        JdbcReviewCaseStore cases = new JdbcReviewCaseStore(
                fixture.database.jdbc(), fixture.database.transactions());
        EngineFreshnessService freshness = new EngineFreshnessService(cases, fixture.reports);

        freshness.invalidateDifferentEngineOwned(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "m0-deterministic-v2");
        freshness.invalidateDifferentEngineOwned(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID,
                bundle.report().engineVersion());

        assertThat(fixture.reports.findOwned(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID,
                bundle.report().reportId()).orElseThrow().freshness())
                .isEqualTo(ReviewReportFreshness.OUTDATED);
        assertThat(cases.findOwned(M005ReportFixture.OWNER, M005ReportFixture.CASE_ID)
                .orElseThrow().currentReportId()).isNull();
    }

    private static ReviewReportAssembler assembler() {
        return new ReviewReportAssembler(
                Clock.fixed(M005ReportFixture.NOW.plusSeconds(2), ZoneOffset.UTC));
    }

    private static FindingDraft cannotFinding() {
        return new FindingDraft(
                FindingType.CANNOT_CONFIRM, CannotConfirmReason.CORRUPTED,
                null, null, "1".repeat(64), "Synthetic input cannot be confirmed",
                List.of(
                        new EvidenceDraft(EvidenceSide.RULE, EvidenceKind.GLOBAL_M0_POLICY,
                                Map.of("ruleId", "GLOBAL_INPUT_RELIABILITY")),
                        new EvidenceDraft(EvidenceSide.MATERIAL, EvidenceKind.MATERIAL_ITEM_SNAPSHOT,
                                Map.of("diagnosticId", "diagnostic-1"))));
    }
}
