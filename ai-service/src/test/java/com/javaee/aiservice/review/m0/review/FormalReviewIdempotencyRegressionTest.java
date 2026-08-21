package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmission;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmissionService;
import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewMode;
import com.javaee.aiservice.review.m0.report.M005ReportFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormalReviewIdempotencyRegressionTest {
    @Test
    void replayUsesFirstEnvelopeWhileDifferentBodyConflictsAndDifferentKeyCapturesRename() {
        TestContext context = context();
        FormalReviewRequest request = request(M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID);
        IdempotencyScope stableKey = scope(M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "stable-key");

        ReviewM0JobSubmission first = context.service.submitOwned(
                stableKey, M005ReportFixture.CASE_ID, request);
        context.provider.put(M005ReportFixture.CASE_ID, captured(
                manifest(M005ReportFixture.CASE_ID, M005ReportFixture.SNAPSHOT_ID,
                        M005ReportFixture.UPLOAD_ID, "after.pdf", 2)));
        ReviewM0JobSubmission replay = context.service.submitOwned(
                stableKey, M005ReportFixture.CASE_ID, request);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.job().jobId()).isEqualTo(first.job().jobId());
        assertThat(replay.job().requestJson()).isEqualTo(first.job().requestJson());
        assertThat(ReviewJobRequestEnvelope.readCanonical(replay.job().requestJson())
                .input().materials().get(0).submissionFileName()).isEqualTo("before.pdf");
        assertThat(context.provider.submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(1);
        assertCounts(context.fixture, 1, 1);

        FormalReviewRequest differentBody = request(
                M005ReportFixture.SNAPSHOT_ID, "different-upload-confirmation");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> context.service.submitOwned(
                    stableKey, M005ReportFixture.CASE_ID, differentBody))
                    .isInstanceOf(ReviewM0Exception.class)
                    .extracting(error -> ((ReviewM0Exception) error).errorCode())
                    .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT);
        }
        assertThat(context.provider.submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(1);
        assertCounts(context.fixture, 1, 1);

        ReviewM0JobSubmission renamed = context.service.submitOwned(
                scope(M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "renamed-key"),
                M005ReportFixture.CASE_ID, request);
        assertThat(renamed.job().jobId()).isNotEqualTo(first.job().jobId());
        assertThat(renamed.job().inputFingerprint()).isNotEqualTo(first.job().inputFingerprint());
        assertThat(ReviewJobRequestEnvelope.readCanonical(renamed.job().requestJson())
                .input().materials().get(0).submissionFileName()).isEqualTo("after.pdf");
        assertThat(context.provider.submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(2);
        assertCounts(context.fixture, 2, 2);
    }

    @Test
    void ownerCaseAndEndpointScopesCannotReplayOrLeaveFailedClaims() {
        TestContext context = context();
        FormalReviewRequest request = request(M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID);
        context.service.submitOwned(
                scope(M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "scoped-key"),
                M005ReportFixture.CASE_ID, request);
        assertCounts(context.fixture, 1, 1);

        assertThatThrownBy(() -> context.service.submitOwned(
                scope("other-owner", M005ReportFixture.CASE_ID, "scoped-key"),
                M005ReportFixture.CASE_ID, request))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
        assertCounts(context.fixture, 1, 1);

        IdempotencyScope wrongEndpoint = new IdempotencyScope(
                M005ReportFixture.OWNER, "POST", "/api/review-m0/cases/"
                + M005ReportFixture.CASE_ID + "/other-operation", "scoped-key");
        assertThatThrownBy(() -> context.service.submitOwned(
                wrongEndpoint, M005ReportFixture.CASE_ID, request))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);
        assertCounts(context.fixture, 1, 1);

        String otherCase = "case-m005-other";
        ReviewM0StoreTestData.createCase(context.fixture.database, otherCase, M005ReportFixture.OWNER);
        ReviewM0StoreTestData.publishSnapshot(
                context.fixture.database, otherCase, M005ReportFixture.OWNER, 0);
        String otherSnapshot = "clsnap-" + otherCase;
        String otherUpload = "upload-" + otherCase;
        context.provider.put(otherCase, captured(manifest(
                otherCase, otherSnapshot, otherUpload, "other.pdf", 1)));
        ReviewM0JobSubmission other = context.service.submitOwned(
                scope(M005ReportFixture.OWNER, otherCase, "scoped-key"), otherCase,
                request(otherSnapshot, otherUpload));
        assertThat(other.replayed()).isFalse();
        assertThat(other.job().caseId()).isEqualTo(otherCase);
        assertCounts(context.fixture, 2, 2);
    }

    @Test
    void concurrentSameKeyAndBodyCreatesOneJobAndCapturesOneManifest() throws Exception {
        TestContext context = context();
        FormalReviewRequest request = request(M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID);
        IdempotencyScope scope = scope(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "concurrent-key");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ReviewM0JobSubmission> first = workers.submit(() ->
                    submitAfterBarrier(context, scope, request, ready, start));
            Future<ReviewM0JobSubmission> second = workers.submit(() ->
                    submitAfterBarrier(context, scope, request, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ReviewM0JobSubmission a = first.get(20, TimeUnit.SECONDS);
            ReviewM0JobSubmission b = second.get(20, TimeUnit.SECONDS);

            assertThat(a.job().jobId()).isEqualTo(b.job().jobId());
            assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(false, true);
            assertThat(context.provider.submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(1);
            assertCounts(context.fixture, 1, 1);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void submissionGateFailureLeavesNoJobOrIdempotencyHalfWrite() {
        M005ReportFixture fixture = new M005ReportFixture();
        MutableProvider provider = new MutableProvider();
        provider.failWith(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED);
        FormalReviewSubmissionService service = service(fixture, provider);

        assertThatThrownBy(() -> service.submitOwned(
                scope(M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "rejected-key"),
                M005ReportFixture.CASE_ID,
                request(M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED);
        assertCounts(fixture, 0, 0);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isZero();
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_evidence_ref", Integer.class)).isZero();
    }

    private static ReviewM0JobSubmission submitAfterBarrier(
            TestContext context,
            IdempotencyScope scope,
            FormalReviewRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start barrier timed out");
        return context.service.submitOwned(scope, M005ReportFixture.CASE_ID, request);
    }

    private static TestContext context() {
        M005ReportFixture fixture = new M005ReportFixture();
        MutableProvider provider = new MutableProvider();
        provider.put(M005ReportFixture.CASE_ID, captured(manifest(
                M005ReportFixture.CASE_ID, M005ReportFixture.SNAPSHOT_ID,
                M005ReportFixture.UPLOAD_ID, "before.pdf", 1)));
        return new TestContext(fixture, provider, service(fixture, provider));
    }

    private static FormalReviewSubmissionService service(
            M005ReportFixture fixture,
            ReviewInputProvider provider
    ) {
        ReviewM0JobSubmissionService jobs = new ReviewM0JobSubmissionService(
                new JdbcReviewM0IdempotencyStore(
                        fixture.database.jdbc(), fixture.database.transactions()),
                fixture.jobs, fixture.database.transactions());
        return new FormalReviewSubmissionService(
                fixture.database.jdbc(), fixture.database.transactions(), provider, fixture.reader,
                new IncrementalReviewValidator(), jobs,
                Clock.fixed(M005ReportFixture.NOW.plusSeconds(2), ZoneOffset.UTC));
    }

    private static FormalReviewRequest request(String snapshotId, String uploadId) {
        return new FormalReviewRequest(ReviewMode.FULL, snapshotId, uploadId, null, List.of());
    }

    private static IdempotencyScope scope(String owner, String caseId, String key) {
        return new IdempotencyScope(
                owner, "POST", "/api/review-m0/cases/" + caseId + "/reviews", key);
    }

    private static ReviewInputManifest manifest(
            String caseId,
            String snapshotId,
            String uploadId,
            String name,
            long nameRevision
    ) {
        return new ReviewInputManifest(
                FormalChecklist.CONTRACT_VERSION, DeterministicReviewEngine.ENGINE_VERSION,
                caseId, 0, List.of(), snapshotId, ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT,
                0, 0, nameRevision, uploadId, ReviewM0StoreTestData.DIGEST_A,
                List.of(new ReviewInputManifest.SourceFact(
                        "source-" + caseId, FormalFileType.PDF, ReviewM0StoreTestData.DIGEST_A, 100)),
                List.of(new ReviewInputManifest.MaterialFact(
                        "material-" + caseId, "source-" + caseId, MaterialKind.TOP_LEVEL_FILE,
                        null, null, ReviewM0StoreTestData.DIGEST_A, FormalFileType.PDF, name,
                        MaterialStatus.AVAILABLE, null, null)),
                List.of(), List.of());
    }

    private static CapturedReviewInput captured(ReviewInputManifest manifest) {
        FormalChecklist checklist = new FormalChecklist(
                manifest.checklistSnapshotId(), "clrev-" + manifest.caseId(),
                manifest.basisRevision(), 1, manifest.checklistSnapshotDigest(), List.of());
        return new CapturedReviewInput(
                checklist,
                new MaterialCollectionSnapshot(
                        manifest.caseId(), manifest.materialSetRevision(), manifest.assignmentRevision(),
                        manifest.nameRevision(), List.of(), List.of(), List.of(), List.of(), null),
                manifest);
    }

    private static void assertCounts(M005ReportFixture fixture, int jobs, int idempotency) {
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_job", Integer.class)).isEqualTo(jobs);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency", Integer.class)).isEqualTo(idempotency);
    }

    private static final class MutableProvider implements ReviewInputProvider {
        private final Map<String, AtomicReference<CapturedReviewInput>> inputs = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> captures = new ConcurrentHashMap<>();
        private volatile ReviewM0ErrorCode failure;

        void put(String caseId, CapturedReviewInput input) {
            inputs.computeIfAbsent(caseId, ignored -> new AtomicReference<>()).set(input);
        }

        void failWith(ReviewM0ErrorCode code) {
            failure = code;
        }

        int submissionCaptures(String caseId) {
            return captures.getOrDefault(caseId, new AtomicInteger()).get();
        }

        @Override
        public CapturedReviewInput captureForSubmission(
                String ownerId,
                String caseId,
                String checklistSnapshotId,
                String uploadConfirmationId
        ) {
            captures.computeIfAbsent(caseId, ignored -> new AtomicInteger()).incrementAndGet();
            if (failure != null) throw new ReviewM0Exception(failure, "synthetic submission rejection");
            CapturedReviewInput input = inputs.getOrDefault(caseId, new AtomicReference<>()).get();
            if (input == null) throw new AssertionError("missing synthetic input for " + caseId);
            return input;
        }

        @Override
        public CapturedReviewInput captureForExecution(
                String ownerId,
                String caseId,
                String checklistSnapshotId,
                String uploadConfirmationId
        ) {
            CapturedReviewInput input = inputs.getOrDefault(caseId, new AtomicReference<>()).get();
            if (input == null) throw new AssertionError("missing synthetic input for " + caseId);
            return input;
        }
    }

    private record TestContext(
            M005ReportFixture fixture,
            MutableProvider provider,
            FormalReviewSubmissionService service
    ) {
    }
}
