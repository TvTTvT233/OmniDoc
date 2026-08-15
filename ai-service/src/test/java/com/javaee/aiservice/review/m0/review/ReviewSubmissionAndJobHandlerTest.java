package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.JdbcReviewM0JobStore;
import com.javaee.aiservice.review.m0.job.ReviewM0JobDispatcher;
import com.javaee.aiservice.review.m0.job.ReviewM0JobHandlerRegistry;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmission;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmissionService;
import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewMode;
import com.javaee.aiservice.review.m0.report.M005ReportFixture;
import com.javaee.aiservice.review.m0.report.ReviewReportAssembler;
import com.javaee.aiservice.review.m0.report.ReviewReportReader;
import com.javaee.aiservice.review.m0.store.ReviewReportStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReviewSubmissionAndJobHandlerTest {
    @ParameterizedTest
    @EnumSource(value = ReviewM0ErrorCode.class, names = {
            "REVIEW_M0_CHECKLIST_NOT_CONFIRMED",
            "REVIEW_M0_CHECKLIST_STALE_BASIS",
            "REVIEW_M0_CONDITIONAL_UNRESOLVED",
            "REVIEW_M0_UPLOAD_NOT_CONFIRMED"
    })
    void candidateStaleConditionalAndUnconfirmedInputsAreRejectedBeforeAnyJobOrReport(
            ReviewM0ErrorCode code
    ) {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewM0JobSubmissionService submission = mock(ReviewM0JobSubmissionService.class);
        ReviewInputProvider rejecting = rejectingProvider(code);
        FormalReviewSubmissionService service = new FormalReviewSubmissionService(
                fixture.database.jdbc(), fixture.database.transactions(), rejecting, fixture.reader,
                new IncrementalReviewValidator(), submission, fixedClock());

        assertThatThrownBy(() -> service.submitOwned(
                scope("gate-" + code), M005ReportFixture.CASE_ID,
                new FormalReviewRequest(
                        ReviewMode.FULL, M005ReportFixture.SNAPSHOT_ID,
                        M005ReportFixture.UPLOAD_ID, null, List.of())))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(code);
        verifyNoInteractions(submission);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_job", Integer.class)).isZero();
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isZero();
    }

    @Test
    void acceptedFullSubmissionPersistsImmutableManifestAndReplaysIdempotently() {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewM0JobSubmissionService jobs = new ReviewM0JobSubmissionService(
                new JdbcReviewM0IdempotencyStore(fixture.database.jdbc(), fixture.database.transactions()),
                fixture.jobs, fixture.database.transactions());
        FormalReviewSubmissionService service = new FormalReviewSubmissionService(
                fixture.database.jdbc(), fixture.database.transactions(), provider(fixture), fixture.reader,
                new IncrementalReviewValidator(), jobs, fixedClock());
        FormalReviewRequest request = new FormalReviewRequest(
                ReviewMode.FULL, M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID,
                null, List.of());
        IdempotencyScope scope = scope("accepted-full");

        ReviewM0JobSubmission first = service.submitOwned(scope, M005ReportFixture.CASE_ID, request);
        ReviewM0JobSubmission replay = service.submitOwned(scope, M005ReportFixture.CASE_ID, request);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.job().jobId()).isEqualTo(first.job().jobId());
        ReviewJobRequestEnvelope stored = ReviewJobRequestEnvelope.readCanonical(first.job().requestJson());
        assertThat(stored.input()).isEqualTo(fixture.input);
        assertThat(first.job().inputFingerprint()).isEqualTo(fixture.input.fingerprint().sha256());
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_job", Integer.class)).isEqualTo(1);
    }

    @Test
    void fullAndIncrementalHandlersOccupyBothM002RegistrySlots() {
        ReviewM0ReviewJobExecutor executor = new ReviewM0ReviewJobExecutor(
                mock(ReviewInputProvider.class), mock(ReviewReportReader.class),
                new IncrementalReviewValidator(),
                new DeterministicReviewEngine(
                        new BoundedRegexMatcher(Duration.ofMillis(100)), new ReviewEvidenceFactory()),
                new ReviewReportAssembler(fixedClock()), mock(ReviewReportStore.class), fixedClock());
        FormalReviewJobHandler full = new FormalReviewJobHandler(executor);
        IncrementalReviewJobHandler incremental = new IncrementalReviewJobHandler(executor);
        ReviewM0JobHandlerRegistry registry = new ReviewM0JobHandlerRegistry(List.of(full, incremental));

        assertThat(registry.find(ReviewJobType.FORMAL_REVIEW)).contains(full);
        assertThat(registry.find(ReviewJobType.INCREMENTAL_REVIEW)).contains(incremental);
    }

    @Test
    void handlerPublishesOneReadyReportAndCompletesItsLeasedJob() {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewJob leased = fixture.createAndLease(
                "job-handler-ready", ReviewJobType.FORMAL_REVIEW, fixture.fullEnvelope());
        ReviewM0ReviewJobExecutor executor = executor(fixture, provider(fixture));
        FormalReviewJobHandler handler = new FormalReviewJobHandler(executor);

        handler.handle(leased, fixture.jobs);

        ReviewJob completed = fixture.jobs.findOwned(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, leased.jobId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(completed.result().resultType()).isEqualTo("REPORT");
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report", Integer.class)).isEqualTo(1);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT assessment FROM review_m0_report", String.class)).isEqualTo("M0_READY");
    }

    @Test
    void fileServiceSystemFailureFailsJobWithoutBusinessFindingOrReport() {
        M005ReportFixture fixture = new M005ReportFixture();
        ReviewJob queued = new ReviewJob(
                "job-system-failure", M005ReportFixture.CASE_ID, M005ReportFixture.OWNER,
                ReviewJobType.FORMAL_REVIEW, ReviewJobStatus.QUEUED,
                fixture.fullEnvelope().canonicalJson(), fixture.fullEnvelope().digest(),
                fixture.input.fingerprint().sha256(), "idem-system-failure",
                0, 1, 0, null, null, null, null, null, 0,
                M005ReportFixture.NOW, null, null, null);
        fixture.jobs.create(queued);
        ReviewInputProvider failing = rejectingProvider(ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE);
        FormalReviewJobHandler handler = new FormalReviewJobHandler(executor(fixture, failing));
        ReviewM0JobDispatcher dispatcher = new ReviewM0JobDispatcher(
                fixture.jobs, new ReviewM0JobHandlerRegistry(List.of(handler)));

        ReviewJob failed = dispatcher.runNext(
                M005ReportFixture.WORKER, M005ReportFixture.NOW.plusSeconds(1),
                Duration.ofMinutes(2), 1).orElseThrow();

        assertThat(failed.status()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.error().errorCode()).isEqualTo(ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isZero();
    }

    private static ReviewM0ReviewJobExecutor executor(
            M005ReportFixture fixture, ReviewInputProvider provider
    ) {
        DeterministicReviewEngine engine = new DeterministicReviewEngine(
                new BoundedRegexMatcher(Duration.ofMillis(100)), new ReviewEvidenceFactory());
        return new ReviewM0ReviewJobExecutor(
                provider, fixture.reader, new IncrementalReviewValidator(), engine,
                new ReviewReportAssembler(fixedClock()), fixture.reports, fixedClock());
    }

    private static ReviewInputProvider provider(M005ReportFixture fixture) {
        FormalChecklist checklist = new FormalChecklist(
                M005ReportFixture.SNAPSHOT_ID, "clrev-" + M005ReportFixture.CASE_ID,
                0, 1, fixture.input.checklistSnapshotDigest(), List.of());
        CapturedReviewInput captured = new CapturedReviewInput(
                checklist,
                new MaterialCollectionSnapshot(
                        M005ReportFixture.CASE_ID, 0, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), null),
                fixture.input);
        return new ReviewInputProvider() {
            @Override
            public CapturedReviewInput captureForSubmission(
                    String ownerId, String caseId, String checklistSnapshotId, String uploadConfirmationId
            ) { return captured; }

            @Override
            public CapturedReviewInput captureForExecution(
                    String ownerId, String caseId, String checklistSnapshotId, String uploadConfirmationId
            ) { return captured; }
        };
    }

    private static ReviewInputProvider rejectingProvider(ReviewM0ErrorCode code) {
        return new ReviewInputProvider() {
            @Override
            public CapturedReviewInput captureForSubmission(
                    String ownerId, String caseId, String checklistSnapshotId, String uploadConfirmationId
            ) { throw new ReviewM0Exception(code, "synthetic gate rejection"); }

            @Override
            public CapturedReviewInput captureForExecution(
                    String ownerId, String caseId, String checklistSnapshotId, String uploadConfirmationId
            ) { throw new ReviewM0Exception(code, "synthetic execution failure"); }
        };
    }

    private static IdempotencyScope scope(String key) {
        return new IdempotencyScope(
                M005ReportFixture.OWNER, "POST",
                "/api/review-m0/cases/" + M005ReportFixture.CASE_ID + "/reviews", key);
    }

    private static Clock fixedClock() {
        return Clock.fixed(M005ReportFixture.NOW.plusSeconds(2), ZoneOffset.UTC);
    }
}
