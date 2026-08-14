package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.job.JdbcReviewM0JobStore;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class ReviewM0StoreTestData {
    public static final Instant T0 = Instant.parse("2026-08-14T00:00:00Z");
    public static final String DIGEST_A = "a".repeat(64);
    public static final String DIGEST_B = "b".repeat(64);
    public static final String DIGEST_EMPTY_OBJECT = CanonicalJson.sha256(java.util.Map.of());

    private ReviewM0StoreTestData() {
    }

    public static ReviewCase createCase(ReviewM0JdbcTestFixture fixture, String caseId, String ownerId) {
        JdbcReviewCaseStore store = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());
        return store.create(new ReviewCase(
                caseId, ownerId, "Synthetic review", ProcessingMode.LOCAL_ONLY,
                0, 0, 0, 0, 0, 0, null, null, T0, T0));
    }

    public static ChecklistSnapshot publishSnapshot(
            ReviewM0JdbcTestFixture fixture,
            String caseId,
            String ownerId,
            long expectedCaseVersion
    ) {
        String revisionId = "clrev-" + caseId;
        String snapshotId = "clsnap-" + caseId;
        fixture.jdbc().update("""
                        INSERT INTO review_m0_checklist_revision(
                            checklist_revision_id, case_id, lifecycle, basis_revision, revision_number,
                            draft_digest, extraction_job_id, created_by, created_at
                        ) VALUES (?, ?, 'CANDIDATE_DRAFT', 0, 1, ?, NULL, ?, ?)
                        """,
                revisionId, caseId, DIGEST_A, ownerId, JdbcReviewCaseStore.timestamp(T0));
        ChecklistSnapshot snapshot = new ChecklistSnapshot(
                snapshotId, caseId, revisionId, 0, 1, "{}", DIGEST_EMPTY_OBJECT,
                ChecklistLifecycle.CONFIRMED, ownerId, T0.plusSeconds(1), null);
        new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions())
                .publishChecklistSnapshotOwned(ownerId, caseId, expectedCaseVersion, snapshot, "CHECKLIST_PUBLISHED");
        fixture.jdbc().update("""
                        INSERT INTO review_m0_upload_confirmation(
                            upload_confirmation_id, case_id, checklist_snapshot_id, material_set_revision,
                            material_set_digest, confirmed_by, confirmed_at
                        ) VALUES (?, ?, ?, 0, ?, ?, ?)
                        """,
                "upload-" + caseId, caseId, snapshotId, DIGEST_A, ownerId,
                JdbcReviewCaseStore.timestamp(T0.plusSeconds(2)));
        return snapshot;
    }

    public static ReviewJob createAndLeaseJob(
            ReviewM0JdbcTestFixture fixture,
            String jobId,
            String caseId,
            String ownerId,
            ReviewJobType type,
            String idempotencyKey,
            String workerId
    ) {
        return createAndLeaseJob(
                fixture, jobId, caseId, ownerId, type, idempotencyKey, workerId, null);
    }

    public static ReviewJob createAndLeaseJob(
            ReviewM0JdbcTestFixture fixture,
            String jobId,
            String caseId,
            String ownerId,
            ReviewJobType type,
            String idempotencyKey,
            String workerId,
            String inputFingerprint
    ) {
        JdbcReviewM0JobStore store = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        store.create(new ReviewJob(
                jobId, caseId, ownerId, type, ReviewJobStatus.QUEUED,
                "{}", DIGEST_EMPTY_OBJECT, inputFingerprint, idempotencyKey, 0, 1, 0,
                null, null, null, null, null, 0, T0, null, null, null));
        return store.acquireNext(workerId, T0.plusSeconds(3), Duration.ofMinutes(1)).orElseThrow();
    }

    public static ReviewReport publishReadyReport(
            ReviewM0JdbcTestFixture fixture,
            String caseId,
            String ownerId,
            String jobId,
            String workerId,
            String fingerprint
    ) {
        ReviewReport report = new ReviewReport(
                "report-" + jobId, caseId, jobId, "REVIEW_M0_CONTRACT_V1", "engine-v1",
                ReviewReportFreshness.CURRENT, ReviewAssessment.M0_READY, fingerprint,
                "clsnap-" + caseId, "upload-" + caseId, 0, 0, 0, 0,
                null, 0, 0, 0, T0.plusSeconds(4), null, null);
        JdbcReviewM0JobStore jobStore = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        new JdbcReviewReportStore(fixture.jdbc(), jobStore, fixture.transactions())
                .publishOwned(ownerId, workerId, T0.plusSeconds(5), new ReportBundle(report, List.of(), List.of()));
        return report;
    }
}
