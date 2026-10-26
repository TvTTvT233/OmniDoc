package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.JdbcReviewM0JobStore;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmissionService;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewFinding;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewReportStoreTest {
    @Test
    void sameCaseAndInputFingerprintPublishesAtMostOneFormalReport() {
        ReviewM0JdbcTestFixture fixture = readyCase("case-idem");
        JdbcReviewM0JobStore jobs = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        JdbcReviewReportStore reports = new JdbcReviewReportStore(fixture.jdbc(), jobs, fixture.transactions());
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-one", "case-idem", "owner", ReviewJobType.FORMAL_REVIEW,
                "idem-one", "worker-one", "a".repeat(64));
        ReviewReport first = report("report-one", "job-one", "case-idem", "a".repeat(64), ReviewAssessment.M0_READY, 0);
        reports.publishOwned("owner", "worker-one", T0.plusSeconds(5), new ReportBundle(first, List.of(), List.of()));

        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-two", "case-idem", "owner", ReviewJobType.FORMAL_REVIEW,
                "idem-two", "worker-two", "a".repeat(64));
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
        JdbcReviewReportStore reports = new JdbcReviewReportStore(fixture.jdbc(), jobs, fixture.transactions());
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-evidence", "case-evidence", "owner",
                ReviewJobType.FORMAL_REVIEW, "idem-evidence", "worker", "b".repeat(64));
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
        JdbcReviewReportStore reports = new JdbcReviewReportStore(fixture.jdbc(), jobs, fixture.transactions());
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-old", "case-engine", "owner", ReviewJobType.FORMAL_REVIEW,
                "idem-old", "worker-old", "d".repeat(64));
        ReviewReport old = report("report-old", "job-old", "case-engine", "d".repeat(64), ReviewAssessment.M0_READY, 0);
        reports.publishOwned("owner", "worker-old", T0.plusSeconds(5), new ReportBundle(old, List.of(), List.of()));
        JdbcReviewCaseStore cases = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());
        var current = cases.findOwned("owner", "case-engine").orElseThrow();
        cases.advanceRevisionOwned(
                "owner", "case-engine", current.version(), RevisionKind.ENGINE_VERSION, "ENGINE_VERSION_CHANGED");
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-new", "case-engine", "owner", ReviewJobType.FORMAL_REVIEW,
                "idem-new", "worker-new", "e".repeat(64));
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

    @Test
    void crossCaseAndOwnerPublicationRollsBackReportJobPointerAndIdempotencyState() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-a", "owner-a");
        ReviewM0StoreTestData.publishSnapshot(fixture, "case-a", "owner-a", 0);
        ReviewM0StoreTestData.createCase(fixture, "case-b", "owner-b");
        ReviewM0StoreTestData.publishSnapshot(fixture, "case-b", "owner-b", 0);
        JdbcReviewM0JobStore jobs = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        ReviewM0JobSubmissionService submissions = new ReviewM0JobSubmissionService(
                new JdbcReviewM0IdempotencyStore(fixture.jdbc(), fixture.transactions()),
                jobs, fixture.transactions());
        String fingerprint = "f".repeat(64);
        submissions.submit(
                new IdempotencyScope("owner-a", "POST", "/api/review-m0/cases/case-a/reviews", "publish"),
                "9".repeat(64), queuedReviewJob("job-a", "case-a", "owner-a", fingerprint));
        ReviewJob leased = jobs.acquireNext("worker-a", T0.plusSeconds(3), java.time.Duration.ofMinutes(1))
                .orElseThrow();
        JdbcReviewReportStore reports = new JdbcReviewReportStore(
                fixture.jdbc(), jobs, fixture.transactions());
        ReviewReport foreignReport = report(
                "report-b", "job-a", "case-b", fingerprint, ReviewAssessment.M0_READY, 0);
        Map<String, Object> idempotencyBefore = fixture.jdbc().queryForMap("""
                SELECT state, response_status, response_type, response_id
                FROM review_m0_idempotency WHERE owner_id = 'owner-a' AND idempotency_key = 'publish'
                """);
        var caseBefore = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions())
                .findOwned("owner-b", "case-b").orElseThrow();

        assertThatThrownBy(() -> reports.publishOwned(
                "owner-b", "worker-a", T0.plusSeconds(4),
                new ReportBundle(foreignReport, List.of(), List.of())))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);

        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(jobs.findOwned("owner-a", "case-a", "job-a")).contains(leased);
        assertThat(new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions())
                .findOwned("owner-b", "case-b")).contains(caseBefore);
        assertThat(fixture.jdbc().queryForMap("""
                SELECT state, response_status, response_type, response_id
                FROM review_m0_idempotency WHERE owner_id = 'owner-a' AND idempotency_key = 'publish'
                """)).isEqualTo(idempotencyBefore);
    }

    @Test
    void reportInputFingerprintMustEqualTheLeasedJobAndFailureHasNoPublicationSideEffect() {
        ReviewM0JdbcTestFixture fixture = readyCase("case-fingerprint");
        JdbcReviewM0JobStore jobs = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        ReviewJob leased = ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-fingerprint", "case-fingerprint", "owner", ReviewJobType.FORMAL_REVIEW,
                "idem-fingerprint", "worker", "1".repeat(64));
        JdbcReviewReportStore reports = new JdbcReviewReportStore(
                fixture.jdbc(), jobs, fixture.transactions());
        ReviewReport mismatched = report(
                "report-fingerprint", "job-fingerprint", "case-fingerprint", "2".repeat(64),
                ReviewAssessment.M0_READY, 0);
        var caseBefore = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions())
                .findOwned("owner", "case-fingerprint").orElseThrow();

        assertThatThrownBy(() -> reports.publishOwned(
                "owner", "worker", T0.plusSeconds(5), new ReportBundle(mismatched, List.of(), List.of())))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT);
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(jobs.findOwned("owner", "case-fingerprint", "job-fingerprint")).contains(leased);
        assertThat(new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions())
                .findOwned("owner", "case-fingerprint")).contains(caseBefore);
    }

    @Test
    void succeededJobCannotBeIdempotentlyReusedToProbeAnotherCaseReport() {
        ReviewM0JdbcTestFixture fixture = new ReviewM0JdbcTestFixture();
        ReviewM0StoreTestData.createCase(fixture, "case-result-a", "owner-a");
        ReviewM0StoreTestData.publishSnapshot(fixture, "case-result-a", "owner-a", 0);
        ReviewM0StoreTestData.createCase(fixture, "case-result-b", "owner-b");
        ReviewM0StoreTestData.publishSnapshot(fixture, "case-result-b", "owner-b", 0);
        JdbcReviewM0JobStore jobs = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, "job-result", "case-result-a", "owner-a", ReviewJobType.FORMAL_REVIEW,
                "idem-result", "worker", "3".repeat(64));
        JdbcReviewReportStore reports = new JdbcReviewReportStore(
                fixture.jdbc(), jobs, fixture.transactions());
        reports.publishOwned(
                "owner-a", "worker", T0.plusSeconds(5),
                new ReportBundle(report(
                        "report-result-a", "job-result", "case-result-a", "3".repeat(64),
                        ReviewAssessment.M0_READY, 0), List.of(), List.of()));
        ReviewJob completed = jobs.findOwned("owner-a", "case-result-a", "job-result").orElseThrow();

        assertThatThrownBy(() -> reports.publishOwned(
                "owner-b", "worker", T0.plusSeconds(6),
                new ReportBundle(report(
                        "report-result-b", "job-result", "case-result-b", "3".repeat(64),
                        ReviewAssessment.M0_READY, 0), List.of(), List.of())))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);
        assertThat(jobs.findOwned("owner-a", "case-result-a", "job-result")).contains(completed);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report WHERE case_id = 'case-result-b'", Integer.class)).isZero();
        assertThat(new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions())
                .findOwned("owner-b", "case-result-b").orElseThrow().currentReportId()).isNull();
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

    private static ReviewJob queuedReviewJob(
            String jobId,
            String caseId,
            String ownerId,
            String inputFingerprint
    ) {
        return new ReviewJob(
                jobId, caseId, ownerId, ReviewJobType.FORMAL_REVIEW, ReviewJobStatus.QUEUED,
                "{}", ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT, inputFingerprint, "idem-" + jobId,
                0, 1, 0, null, null, null, null, null,
                0, T0, null, null, null);
    }
}
