package com.javaee.aiservice.review.m0.report;

import com.javaee.aiservice.review.m0.job.JdbcReviewM0JobStore;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.review.FormalChecklist;
import com.javaee.aiservice.review.m0.review.FormalReviewRequest;
import com.javaee.aiservice.review.m0.review.ReviewInputManifest;
import com.javaee.aiservice.review.m0.review.ReviewJobRequestEnvelope;
import com.javaee.aiservice.review.m0.store.JdbcReviewCaseStore;
import com.javaee.aiservice.review.m0.store.JdbcReviewReportStore;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class M005ReportFixture {
    public static final String OWNER = "owner-1";
    public static final String CASE_ID = "case-m005";
    public static final String SNAPSHOT_ID = "clsnap-" + CASE_ID;
    public static final String UPLOAD_ID = "upload-" + CASE_ID;
    public static final String WORKER = "worker-m005";
    public static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    public final ReviewM0JdbcTestFixture database = new ReviewM0JdbcTestFixture();
    public final JdbcReviewM0JobStore jobs = new JdbcReviewM0JobStore(database.jdbc(), database.transactions());
    public final JdbcReviewReportStore reports = new JdbcReviewReportStore(
            database.jdbc(), jobs, database.transactions());
    public final JdbcReviewReportReader reader = new JdbcReviewReportReader(database.jdbc(), reports, jobs);
    public final ReviewInputManifest input;

    public M005ReportFixture() {
        ReviewM0StoreTestData.createCase(database, CASE_ID, OWNER);
        ReviewM0StoreTestData.publishSnapshot(database, CASE_ID, OWNER, 0);
        input = new ReviewInputManifest(
                FormalChecklist.CONTRACT_VERSION,
                com.javaee.aiservice.review.m0.review.DeterministicReviewEngine.ENGINE_VERSION,
                CASE_ID, 0, List.of(), SNAPSHOT_ID, ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT,
                0, 0, 0, UPLOAD_ID, ReviewM0StoreTestData.DIGEST_A,
                List.of(), List.of(), List.of(), List.of());
    }

    public ReviewJob createAndLease(String jobId, ReviewJobType type, ReviewJobRequestEnvelope envelope) {
        ReviewJob queued = new ReviewJob(
                jobId, CASE_ID, OWNER, type, ReviewJobStatus.QUEUED,
                envelope.canonicalJson(), envelope.digest(), envelope.input().fingerprint().sha256(),
                "idem-" + jobId, 0, 1, 0, null, null, null, null, null, 0,
                NOW, null, null, null);
        jobs.create(queued);
        return jobs.acquireNext(WORKER, NOW.plusSeconds(1), Duration.ofMinutes(5)).orElseThrow();
    }

    public ReviewJobRequestEnvelope fullEnvelope() {
        return new ReviewJobRequestEnvelope(
                new FormalReviewRequest(
                        com.javaee.aiservice.review.m0.model.ReviewMode.FULL,
                        SNAPSHOT_ID, UPLOAD_ID, null, List.of()),
                input);
    }
}
