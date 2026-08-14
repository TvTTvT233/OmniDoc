package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.job.JdbcReviewM0JobStore;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewFinding;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewReportStoreTest {
    @Test
    void sameCaseAndInputFingerprintPublishesAtMostOneFormalReport() {
        ReviewM0JdbcTestFixture fixture = readyCase("case-idem");
        JdbcReviewM0JobStore jobs = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        JdbcReviewReportStore reports = new JdbcReviewReportStore(fixture.jdbc(), jobs);
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-one", "case-idem", "owner", ReviewJobType.FORMAL_REVIEW, "idem-one", "worker-one");
        ReviewReport first = report("report-one", "job-one", "case-idem", "a".repeat(64), ReviewAssessment.M0_READY, 0);
        reports.publishOwned("owner", "worker-one", T0.plusSeconds(5), new ReportBundle(first, List.of(), List.of()));

        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-two", "case-idem", "owner", ReviewJobType.FORMAL_REVIEW, "idem-two", "worker-two");
        ReviewReport duplicate = report("report-two", "job-two", "case-idem", "a".repeat(64), ReviewAssessment.M0_READY, 0);
        ReviewJob replay = reports.publishOwned(
                "owner", "worker-two", T0.plusSeconds(6), new ReportBundle(duplicate, List.of(), List.of()));

        assertThat(replay.result().resultId()).isEqualTo("report-one");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report WHERE case_id = 'case-idem'",
                Integer.class)).isEqualTo(1);
        assertThat(reports.findCurrentOwned("owner", "case-idem")).contains(first);
    }

    @Test
    void deterministicFindingWithoutBothEvidenceSidesCannotPublish() {
        ReviewM0JdbcTestFixture fixture = readyCase("case-evidence");
        JdbcReviewM0JobStore jobs = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        JdbcReviewReportStore reports = new JdbcReviewReportStore(fixture.jdbc(), jobs);
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-evidence", "case-evidence", "owner",
                ReviewJobType.FORMAL_REVIEW, "idem-evidence", "worker");
        ReviewReport report = report(
                "report-evidence", "job-evidence", "case-evidence", "b".repeat(64),
                ReviewAssessment.M0_NOT_READY, 1);
        ReviewFinding finding = new ReviewFinding(
                "finding", report.reportId(), FindingType.MISSING, null,
                null, null, "c".repeat(64), "Synthetic missing", T0.plusSeconds(4));

        assertThatThrownBy(() -> reports.publishOwned(
                "owner", "worker", T0.plusSeconds(5), new ReportBundle(report, List.of(finding), List.of())))
                .isInstanceOf(ReviewM0Exception.class)
                .hasMessageContaining("rule and material evidence");
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(jobs.findOwned("owner", "case-evidence", "job-evidence").orElseThrow().status().name())
                .isEqualTo("RUNNING");
    }

    @Test
    void engineRevisionExpiresOldReportAndNewPublicationNeverRestoresIt() {
        ReviewM0JdbcTestFixture fixture = readyCase("case-engine");
        JdbcReviewM0JobStore jobs = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        JdbcReviewReportStore reports = new JdbcReviewReportStore(fixture.jdbc(), jobs);
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-old", "case-engine", "owner", ReviewJobType.FORMAL_REVIEW, "idem-old", "worker-old");
        ReviewReport old = report("report-old", "job-old", "case-engine", "d".repeat(64), ReviewAssessment.M0_READY, 0);
        reports.publishOwned("owner", "worker-old", T0.plusSeconds(5), new ReportBundle(old, List.of(), List.of()));
        JdbcReviewCaseStore cases = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());
        var current = cases.findOwned("owner", "case-engine").orElseThrow();
        cases.advanceRevisionOwned(
                "owner", "case-engine", current.version(), RevisionKind.ENGINE_VERSION, "ENGINE_VERSION_CHANGED");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-new", "case-engine", "owner", ReviewJobType.FORMAL_REVIEW, "idem-new", "worker-new");
        ReviewReport next = report("report-new", "job-new", "case-engine", "e".repeat(64), ReviewAssessment.M0_READY, 0);
        reports.publishOwned("owner", "worker-new", T0.plusSeconds(8), new ReportBundle(next, List.of(), List.of()));

        assertThat(reports.findOwned("owner", "case-engine", "report-old").orElseThrow().freshness())
                .isEqualTo(ReviewReportFreshness.OUTDATED);
        assertThat(reports.findCurrentOwned("owner", "case-engine")).contains(next);
        assertThat(reports.findOwned("other", "case-engine", "report-new")).isEmpty();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report WHERE case_id = 'case-engine' AND freshness = 'CURRENT'",
                Integer.class)).isEqualTo(1);
    }

    private static ReviewM0JdbcTestFixture readyCase(String caseId) {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, caseId, "owner");
        ReviewM0StoreTestData.publishSnapshot(fixture, caseId, "owner", 0);
        return fixture;
    }

    private static ReviewReport report(
            String reportId,
            String jobId,
            String caseId,
            String fingerprint,
            ReviewAssessment assessment,
            int missingCount
    ) {
        return new ReviewReport(
                reportId, caseId, jobId, "REVIEW_M0_CONTRACT_V1", "engine-v1",
                ReviewReportFreshness.CURRENT, assessment, fingerprint,
                "clsnap-" + caseId, "upload-" + caseId,
                0, 0, 0, 0, null, missingCount, 0, 0,
                T0.plusSeconds(4), null, null);
    }
}
