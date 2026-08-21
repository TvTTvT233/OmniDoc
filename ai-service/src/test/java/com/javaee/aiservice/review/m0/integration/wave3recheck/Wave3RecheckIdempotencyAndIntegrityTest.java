package com.javaee.aiservice.review.m0.integration.wave3recheck;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.archive.ZipArchiveEntryOpener;
import com.javaee.aiservice.review.m0.archive.ZipArchiveInspector;
import com.javaee.aiservice.review.m0.checklist.ChecklistDraftView;
import com.javaee.aiservice.review.m0.checklist.M003TestFixture;
import com.javaee.aiservice.review.m0.evidence.ReviewEvidenceFactory;
import com.javaee.aiservice.review.m0.file.FormalContentInspector;
import com.javaee.aiservice.review.m0.file.InMemoryOwnedFileByteSource;
import com.javaee.aiservice.review.m0.file.SyntheticFileFixtures;
import com.javaee.aiservice.review.m0.file.VerifiedReviewM0FileAccess;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.ReviewM0JobDispatcher;
import com.javaee.aiservice.review.m0.job.ReviewM0JobHandlerRegistry;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmission;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmissionService;
import com.javaee.aiservice.review.m0.material.JdbcReviewM0MaterialRepository;
import com.javaee.aiservice.review.m0.material.MaterialAttachmentResult;
import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;
import com.javaee.aiservice.review.m0.material.MaterialLifecycleService;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewMode;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;
import com.javaee.aiservice.review.m0.report.JdbcReviewReportReader;
import com.javaee.aiservice.review.m0.report.M005ReportFixture;
import com.javaee.aiservice.review.m0.report.ReviewReportAssembler;
import com.javaee.aiservice.review.m0.review.BoundedRegexMatcher;
import com.javaee.aiservice.review.m0.review.CapturedReviewInput;
import com.javaee.aiservice.review.m0.review.CurrentReviewInputProvider;
import com.javaee.aiservice.review.m0.review.DeterministicReviewEngine;
import com.javaee.aiservice.review.m0.review.FormalChecklist;
import com.javaee.aiservice.review.m0.review.FormalReviewJobHandler;
import com.javaee.aiservice.review.m0.review.FormalReviewRequest;
import com.javaee.aiservice.review.m0.review.FormalReviewSubmissionService;
import com.javaee.aiservice.review.m0.review.IncrementalReviewJobHandler;
import com.javaee.aiservice.review.m0.review.IncrementalReviewValidator;
import com.javaee.aiservice.review.m0.review.ReviewInputManifest;
import com.javaee.aiservice.review.m0.review.ReviewInputProvider;
import com.javaee.aiservice.review.m0.review.ReviewJobRequestEnvelope;
import com.javaee.aiservice.review.m0.review.ReviewM0ReviewJobExecutor;
import com.javaee.aiservice.review.m0.store.JdbcReviewReportStore;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** Independent adversarial coverage for HTTP idempotency, transaction ordering and persisted metadata. */
class Wave3RecheckIdempotencyAndIntegrityTest {
    private static final String OTHER_OWNER = "wave3recheck-other-owner";

    @Test
    void repeatedRevisionsAlwaysReplayTheFirstJobEnvelopeWithoutRecapturingManifest() {
        SubmissionContext context = submissionContext();
        FormalReviewRequest request = request(
                M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID);
        IdempotencyScope scope = scope(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "multi-revision-key");

        ReviewM0JobSubmission first = context.service().submitOwned(
                scope, M005ReportFixture.CASE_ID, request);
        context.provider().put(M005ReportFixture.CASE_ID, captured(manifest(
                M005ReportFixture.CASE_ID, M005ReportFixture.SNAPSHOT_ID,
                M005ReportFixture.UPLOAD_ID, "second.pdf", 2)));
        ReviewM0JobSubmission second = context.service().submitOwned(
                scope, M005ReportFixture.CASE_ID, request);
        context.provider().put(M005ReportFixture.CASE_ID, captured(manifest(
                M005ReportFixture.CASE_ID, M005ReportFixture.SNAPSHOT_ID,
                M005ReportFixture.UPLOAD_ID, "third.pdf", 3)));
        ReviewM0JobSubmission third = context.service().submitOwned(
                scope, M005ReportFixture.CASE_ID, request);

        assertThat(List.of(second, third)).allSatisfy(replay -> {
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.job().jobId()).isEqualTo(first.job().jobId());
            assertThat(replay.job().requestJson()).isEqualTo(first.job().requestJson());
            assertThat(replay.job().inputFingerprint()).isEqualTo(first.job().inputFingerprint());
        });
        ReviewJobRequestEnvelope envelope = ReviewJobRequestEnvelope.readCanonical(
                first.job().requestJson());
        assertThat(envelope.input().materials().get(0).submissionFileName()).isEqualTo("first.pdf");
        assertThat(envelope.input().nameRevision()).isEqualTo(1);
        assertThat(first.job().inputFingerprint())
                .isEqualTo(envelope.input().fingerprint().sha256());
        assertThat(context.provider().submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(1);
        assertCounts(context.fixture(), 1, 1, 0, 0, 0);
        assertCurrentPointerNull(context.fixture(), M005ReportFixture.CASE_ID, M005ReportFixture.OWNER);
    }

    @Test
    void clientDigestStaysManifestFreeWhileDifferentBodyConflictsAndNewKeysCaptureFreshState() {
        SubmissionContext context = submissionContext();
        FormalReviewRequest request = request(
                M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID);
        IdempotencyScope firstScope = scope(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "first-key");
        ReviewM0JobSubmission first = context.service().submitOwned(
                firstScope, M005ReportFixture.CASE_ID, request);

        context.provider().put(M005ReportFixture.CASE_ID, captured(manifest(
                M005ReportFixture.CASE_ID, M005ReportFixture.SNAPSHOT_ID,
                M005ReportFixture.UPLOAD_ID, "after.pdf", 2)));
        assertThatThrownBy(() -> context.service().submitOwned(
                firstScope,
                M005ReportFixture.CASE_ID,
                request(M005ReportFixture.SNAPSHOT_ID, "different-http-body")))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT);
        assertThat(context.provider().submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(1);

        IdempotencyScope secondScope = scope(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "second-key");
        ReviewM0JobSubmission second = context.service().submitOwned(
                secondScope, M005ReportFixture.CASE_ID, request);
        List<String> clientDigests = context.fixture().database.jdbc().queryForList(
                "SELECT request_digest FROM review_m0_idempotency ORDER BY idempotency_key",
                String.class);

        assertThat(clientDigests).hasSize(2).allMatch(clientDigests.get(0)::equals);
        assertThat(second.job().jobId()).isNotEqualTo(first.job().jobId());
        assertThat(second.job().requestDigest()).isNotEqualTo(first.job().requestDigest());
        assertThat(second.job().inputFingerprint()).isNotEqualTo(first.job().inputFingerprint());
        assertThat(ReviewJobRequestEnvelope.readCanonical(second.job().requestJson())
                .input().materials().get(0).submissionFileName()).isEqualTo("after.pdf");
        assertThat(context.provider().submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(2);
        assertCounts(context.fixture(), 2, 2, 0, 0, 0);
    }

    @Test
    void ownerCaseAndEndpointScopesCannotReplayOrCreateAProbeableSideChannel() {
        SubmissionContext context = submissionContext();
        FormalReviewRequest request = request(
                M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID);
        context.service().submitOwned(
                scope(M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "scoped-key"),
                M005ReportFixture.CASE_ID,
                request);

        assertThatThrownBy(() -> context.service().submitOwned(
                scope(OTHER_OWNER, M005ReportFixture.CASE_ID, "scoped-key"),
                M005ReportFixture.CASE_ID,
                request))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
        IdempotencyScope wrongEndpoint = new IdempotencyScope(
                M005ReportFixture.OWNER,
                "POST",
                "/api/review-m0/cases/" + M005ReportFixture.CASE_ID + "/other",
                "scoped-key");
        assertThatThrownBy(() -> context.service().submitOwned(
                wrongEndpoint, M005ReportFixture.CASE_ID, request))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);

        String otherCase = "case-wave3recheck-other";
        String otherSnapshot = "clsnap-" + otherCase;
        String otherUpload = "upload-" + otherCase;
        ReviewM0StoreTestData.createCase(context.fixture().database, otherCase, M005ReportFixture.OWNER);
        ReviewM0StoreTestData.publishSnapshot(
                context.fixture().database, otherCase, M005ReportFixture.OWNER, 0);
        context.provider().put(otherCase, captured(manifest(
                otherCase, otherSnapshot, otherUpload, "other.pdf", 1)));
        ReviewM0JobSubmission other = context.service().submitOwned(
                scope(M005ReportFixture.OWNER, otherCase, "scoped-key"),
                otherCase,
                request(otherSnapshot, otherUpload));

        assertThat(other.replayed()).isFalse();
        assertThat(other.job().caseId()).isEqualTo(otherCase);
        assertCounts(context.fixture(), 2, 2, 0, 0, 0);
        assertThat(context.fixture().database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency WHERE owner_id = ?",
                Integer.class,
                OTHER_OWNER)).isZero();
    }

    @Test
    void concurrentSameKeyAndBodyCreatesOneJobAndOneImmutableEnvelope() throws Exception {
        SubmissionContext context = submissionContext();
        FormalReviewRequest request = request(
                M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID);
        IdempotencyScope scope = scope(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "four-way-key");
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<ReviewM0JobSubmission>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent start barrier timed out");
                    }
                    return context.service().submitOwned(
                            scope, M005ReportFixture.CASE_ID, request);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ReviewM0JobSubmission> results = new ArrayList<>();
            for (Future<ReviewM0JobSubmission> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).extracting(result -> result.job().jobId()).containsOnly(
                    results.get(0).job().jobId());
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).filteredOn(ReviewM0JobSubmission::replayed).hasSize(3);
            assertThat(context.provider().submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(1);
            assertCounts(context.fixture(), 1, 1, 0, 0, 0);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedFirstGateDoesNotReserveKeyAndLaterInvalidStateCannotBreakSuccessfulReplay() {
        M005ReportFixture fixture = new M005ReportFixture();
        MutableProvider provider = new MutableProvider();
        provider.failWith(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED);
        FormalReviewSubmissionService service = service(fixture, provider);
        FormalReviewRequest request = request(
                M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID);
        IdempotencyScope scope = scope(
                M005ReportFixture.OWNER, M005ReportFixture.CASE_ID, "recoverable-key");

        assertThatThrownBy(() -> service.submitOwned(scope, M005ReportFixture.CASE_ID, request))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED);
        assertCounts(fixture, 0, 0, 0, 0, 0);
        assertCurrentPointerNull(fixture, M005ReportFixture.CASE_ID, M005ReportFixture.OWNER);

        provider.clearFailure();
        provider.put(M005ReportFixture.CASE_ID, captured(manifest(
                M005ReportFixture.CASE_ID, M005ReportFixture.SNAPSHOT_ID,
                M005ReportFixture.UPLOAD_ID, "valid.pdf", 1)));
        ReviewM0JobSubmission first = service.submitOwned(
                scope, M005ReportFixture.CASE_ID, request);
        provider.failWith(ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_STALE_BASIS);
        ReviewM0JobSubmission replay = service.submitOwned(
                scope, M005ReportFixture.CASE_ID, request);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.job().jobId()).isEqualTo(first.job().jobId());
        assertThat(provider.submissionCaptures(M005ReportFixture.CASE_ID)).isEqualTo(2);
        assertCounts(fixture, 1, 1, 0, 0, 0);
    }

    @Test
    void eachRejectedSubmissionPathRollsBackEveryFormalTableAndCasePointer() {
        for (ReviewM0ErrorCode failure : List.of(
                ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED,
                ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_STALE_BASIS,
                ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE)) {
            M005ReportFixture fixture = new M005ReportFixture();
            MutableProvider provider = new MutableProvider();
            provider.failWith(failure);
            FormalReviewSubmissionService service = service(fixture, provider);

            assertThatThrownBy(() -> service.submitOwned(
                    scope(M005ReportFixture.OWNER, M005ReportFixture.CASE_ID,
                            "failure-" + failure),
                    M005ReportFixture.CASE_ID,
                    request(M005ReportFixture.SNAPSHOT_ID, M005ReportFixture.UPLOAD_ID)))
                    .isInstanceOf(ReviewM0Exception.class)
                    .extracting(error -> ((ReviewM0Exception) error).errorCode())
                    .isEqualTo(failure);
            assertCounts(fixture, 0, 0, 0, 0, 0);
            assertCurrentPointerNull(fixture, M005ReportFixture.CASE_ID, M005ReportFixture.OWNER);
        }
    }

    @Test
    void logicalRenameRacingFirstSubmissionSerializesWithoutConflictOrHalfWrite() throws Exception {
        IntegratedContext context = integratedContext("wave3recheck-race");
        putPdf(context, "race-file", "SYNTHETIC_RACE_BODY", "synthetic/race/object");
        MaterialAttachmentResult attachment = context.materials().attachOwned(
                context.owner(), context.caseId(), "race-file", "before.pdf");
        SubmissionMaterial material = attachment.materials().get(0);
        UploadConfirmation upload = context.materials().confirmUploadOwned(
                context.owner(), context.caseId(), context.snapshot().checklistSnapshotId(), 1, true);
        FormalReviewRequest request = new FormalReviewRequest(
                ReviewMode.FULL,
                context.snapshot().checklistSnapshotId(),
                upload.uploadConfirmationId(),
                null,
                List.of());
        IdempotencyScope scope = scope(context.owner(), context.caseId(), "race-key");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ReviewM0JobSubmission> submitted = workers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("race start timed out");
                return context.submission().submitOwned(scope, context.caseId(), request);
            });
            Future<SubmissionMaterial> renamed = workers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("race start timed out");
                return context.materials().renameOwned(
                        context.owner(), context.caseId(), material.materialId(), material.version(),
                        "after.pdf");
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ReviewM0JobSubmission first = submitted.get(30, TimeUnit.SECONDS);
            SubmissionMaterial after = renamed.get(30, TimeUnit.SECONDS);
            ReviewM0JobSubmission replay = context.submission().submitOwned(
                    scope, context.caseId(), request);

            assertThat(after.submissionFileName()).isEqualTo("after.pdf");
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.job().jobId()).isEqualTo(first.job().jobId());
            assertThat(ReviewJobRequestEnvelope.readCanonical(first.job().requestJson())
                    .input().materials().get(0).submissionFileName())
                    .isIn("before.pdf", "after.pdf");
            assertThat(context.fixture().caseStore().findOwned(
                    context.owner(), context.caseId()).orElseThrow().nameRevision()).isEqualTo(1);
            assertIntegratedCounts(context, 1, 1, 0, 0, 0);
            assertThat(context.fixture().caseStore().findOwned(
                    context.owner(), context.caseId()).orElseThrow().currentReportId()).isNull();
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void persistedJobReportEvidenceAndExceptionsDoNotLeakBodyObjectKeyOrPayload() {
        IntegratedContext context = integratedContext("wave3recheck-redaction");
        String bodyMarker = "SYNTHETIC_PRIVATE_BODY_MARKER_7F31";
        String objectMarker = "synthetic-private-object-path-7f31";
        putPdf(context, "redaction-file", bodyMarker, objectMarker);
        context.materials().attachOwned(
                context.owner(), context.caseId(), "redaction-file", "safe.pdf");
        UploadConfirmation upload = context.materials().confirmUploadOwned(
                context.owner(), context.caseId(), context.snapshot().checklistSnapshotId(), 1, true);
        FormalReviewRequest request = new FormalReviewRequest(
                ReviewMode.FULL,
                context.snapshot().checklistSnapshotId(),
                upload.uploadConfirmationId(),
                null,
                List.of());
        IdempotencyScope scope = scope(context.owner(), context.caseId(), "redaction-key");
        ReviewM0JobSubmission submitted = context.submission().submitOwned(
                scope, context.caseId(), request);
        ReviewJob completed = context.dispatcher().runNext(
                "redaction-worker", M003TestFixture.NOW, Duration.ofMinutes(5), 1).orElseThrow();

        assertThat(completed.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(completed.jobId()).isEqualTo(submitted.job().jobId());
        List<String> persisted = new ArrayList<>();
        persisted.addAll(context.fixture().database().jdbc().queryForList(
                "SELECT request_json FROM review_m0_job", String.class));
        persisted.addAll(context.fixture().database().jdbc().queryForList(
                "SELECT request_digest FROM review_m0_idempotency", String.class));
        persisted.addAll(context.fixture().database().jdbc().queryForList(
                "SELECT message FROM review_m0_finding", String.class));
        persisted.addAll(context.fixture().database().jdbc().queryForList(
                "SELECT ref_json FROM review_m0_evidence_ref", String.class));
        assertThat(persisted).isNotEmpty().allSatisfy(value -> assertThat(value)
                .doesNotContain(bodyMarker)
                .doesNotContain(objectMarker)
                .doesNotContainIgnoringCase("objectKey")
                .doesNotContainIgnoringCase("presigned")
                .doesNotContainIgnoringCase("remotePayload")
                .doesNotContainIgnoringCase("previewText"));

        assertThatThrownBy(() -> context.submission().submitOwned(
                scope,
                context.caseId(),
                new FormalReviewRequest(
                        ReviewMode.FULL,
                        context.snapshot().checklistSnapshotId(),
                        "different-body",
                        null,
                        List.of())))
                .isInstanceOf(ReviewM0Exception.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain(bodyMarker)
                        .doesNotContain(objectMarker));
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_job WHERE error_message IS NOT NULL",
                Integer.class)).isZero();
    }

    private static SubmissionContext submissionContext() {
        M005ReportFixture fixture = new M005ReportFixture();
        MutableProvider provider = new MutableProvider();
        provider.put(M005ReportFixture.CASE_ID, captured(manifest(
                M005ReportFixture.CASE_ID, M005ReportFixture.SNAPSHOT_ID,
                M005ReportFixture.UPLOAD_ID, "first.pdf", 1)));
        return new SubmissionContext(fixture, provider, service(fixture, provider));
    }

    private static FormalReviewSubmissionService service(
            M005ReportFixture fixture,
            ReviewInputProvider provider
    ) {
        ReviewM0JobSubmissionService jobs = new ReviewM0JobSubmissionService(
                new JdbcReviewM0IdempotencyStore(
                        fixture.database.jdbc(), fixture.database.transactions()),
                fixture.jobs,
                fixture.database.transactions());
        return new FormalReviewSubmissionService(
                fixture.database.jdbc(),
                fixture.database.transactions(),
                provider,
                fixture.reader,
                new IncrementalReviewValidator(),
                jobs,
                Clock.fixed(M005ReportFixture.NOW.plusSeconds(2), ZoneOffset.UTC));
    }

    private static IntegratedContext integratedContext(String caseId) {
        String owner = "owner-" + caseId;
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase(caseId, owner, ProcessingMode.LOCAL_ONLY);
        String basisId = "basis-" + caseId;
        fixture.attachReadyBasis(owner, caseId, 0, basisId);
        ChecklistDraftView draft = fixture.checklistService().replaceOfficialCandidatesOwned(
                owner,
                caseId,
                1,
                null,
                List.of(fixture.officialCandidate(basisId, "Required material")));
        ReviewCase current = fixture.caseStore().findOwned(owner, caseId).orElseThrow();
        ChecklistSnapshot snapshot = fixture.checklistService().publishOwned(
                owner,
                caseId,
                current.version(),
                draft.revision(),
                Set.of(draft.items().get(0).requirementId()));

        JdbcReviewM0MaterialRepository repository = new JdbcReviewM0MaterialRepository(
                fixture.database().jdbc());
        InMemoryOwnedFileByteSource bytes = new InMemoryOwnedFileByteSource();
        ArchiveSafetyLimits limits = ArchiveSafetyLimits.secureDefaults();
        FormalContentInspector inspector = new FormalContentInspector(limits);
        MaterialLifecycleService materials = new MaterialLifecycleService(
                repository,
                fixture.caseStore(),
                new VerifiedReviewM0FileAccess(bytes, new ZipArchiveEntryOpener(limits)),
                new ZipArchiveInspector(limits, inspector),
                inspector,
                fixture.database().transactions());
        CurrentReviewInputProvider provider = new CurrentReviewInputProvider(
                fixture.caseStore(),
                fixture.checklistService(),
                fixture.checklistStore(),
                materials,
                DeterministicReviewEngine.ENGINE_VERSION);
        JdbcReviewReportStore reports = new JdbcReviewReportStore(
                fixture.database().jdbc(), fixture.jobStore(), fixture.database().transactions());
        JdbcReviewReportReader reader = new JdbcReviewReportReader(
                fixture.database().jdbc(), reports, fixture.jobStore());
        IncrementalReviewValidator incremental = new IncrementalReviewValidator();
        ReviewM0JobSubmissionService jobSubmission = new ReviewM0JobSubmissionService(
                new JdbcReviewM0IdempotencyStore(
                        fixture.database().jdbc(), fixture.database().transactions()),
                fixture.jobStore(),
                fixture.database().transactions());
        FormalReviewSubmissionService submission = new FormalReviewSubmissionService(
                fixture.database().jdbc(),
                fixture.database().transactions(),
                provider,
                reader,
                incremental,
                jobSubmission,
                M003TestFixture.CLOCK);
        DeterministicReviewEngine engine = new DeterministicReviewEngine(
                new BoundedRegexMatcher(Duration.ofMillis(250)), new ReviewEvidenceFactory());
        ReviewM0ReviewJobExecutor executor = new ReviewM0ReviewJobExecutor(
                provider,
                reader,
                incremental,
                engine,
                new ReviewReportAssembler(M003TestFixture.CLOCK),
                reports,
                M003TestFixture.CLOCK);
        ReviewM0JobDispatcher dispatcher = new ReviewM0JobDispatcher(
                fixture.jobStore(),
                new ReviewM0JobHandlerRegistry(List.of(
                        new FormalReviewJobHandler(executor),
                        new IncrementalReviewJobHandler(executor))));
        return new IntegratedContext(
                fixture, materials, bytes, owner, caseId, snapshot, provider, submission, dispatcher);
    }

    private static void putPdf(
            IntegratedContext context,
            String fileId,
            String bodyMarker,
            String objectMarker
    ) {
        context.bytes().put(
                context.owner(),
                fileId,
                fileId + ".pdf",
                "application/pdf",
                SyntheticFileFixtures.pdf(bodyMarker),
                "storage-v1",
                objectMarker);
    }

    private static FormalReviewRequest request(String snapshotId, String uploadId) {
        return new FormalReviewRequest(
                ReviewMode.FULL, snapshotId, uploadId, null, List.of());
    }

    private static IdempotencyScope scope(String owner, String caseId, String key) {
        return new IdempotencyScope(
                owner,
                "POST",
                "/api/review-m0/cases/" + caseId + "/reviews",
                key);
    }

    private static ReviewInputManifest manifest(
            String caseId,
            String snapshotId,
            String uploadId,
            String name,
            long nameRevision
    ) {
        return new ReviewInputManifest(
                FormalChecklist.CONTRACT_VERSION,
                DeterministicReviewEngine.ENGINE_VERSION,
                caseId,
                0,
                List.of(),
                snapshotId,
                ReviewM0StoreTestData.DIGEST_EMPTY_OBJECT,
                0,
                0,
                nameRevision,
                uploadId,
                ReviewM0StoreTestData.DIGEST_A,
                List.of(new ReviewInputManifest.SourceFact(
                        "source-" + caseId,
                        FormalFileType.PDF,
                        ReviewM0StoreTestData.DIGEST_A,
                        100)),
                List.of(new ReviewInputManifest.MaterialFact(
                        "material-" + caseId,
                        "source-" + caseId,
                        MaterialKind.TOP_LEVEL_FILE,
                        null,
                        null,
                        ReviewM0StoreTestData.DIGEST_A,
                        FormalFileType.PDF,
                        name,
                        MaterialStatus.AVAILABLE,
                        null,
                        null)),
                List.of(),
                List.of());
    }

    private static CapturedReviewInput captured(ReviewInputManifest manifest) {
        return new CapturedReviewInput(
                new FormalChecklist(
                        manifest.checklistSnapshotId(),
                        "clrev-" + manifest.caseId(),
                        manifest.basisRevision(),
                        1,
                        manifest.checklistSnapshotDigest(),
                        List.of()),
                new MaterialCollectionSnapshot(
                        manifest.caseId(),
                        manifest.materialSetRevision(),
                        manifest.assignmentRevision(),
                        manifest.nameRevision(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null),
                manifest);
    }

    private static void assertCounts(
            M005ReportFixture fixture,
            int jobs,
            int idempotency,
            int reports,
            int findings,
            int evidence
    ) {
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_job", Integer.class)).isEqualTo(jobs);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_idempotency", Integer.class)).isEqualTo(idempotency);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report", Integer.class)).isEqualTo(reports);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isEqualTo(findings);
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_evidence_ref", Integer.class)).isEqualTo(evidence);
    }

    private static void assertCurrentPointerNull(
            M005ReportFixture fixture,
            String caseId,
            String owner
    ) {
        assertThat(fixture.database.jdbc().queryForObject(
                "SELECT current_report_id FROM review_m0_case WHERE case_id = ? AND user_id = ?",
                String.class,
                caseId,
                owner)).isNull();
    }

    private static void assertIntegratedCounts(
            IntegratedContext context,
            int jobs,
            int idempotency,
            int reports,
            int findings,
            int evidence
    ) {
        Map<String, Integer> expected = Map.of(
                "review_m0_job", jobs,
                "review_m0_idempotency", idempotency,
                "review_m0_report", reports,
                "review_m0_finding", findings,
                "review_m0_evidence_ref", evidence);
        expected.forEach((table, count) -> assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(count));
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

        void clearFailure() {
            failure = null;
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
            if (failure != null) {
                throw new ReviewM0Exception(failure, "synthetic gate rejection");
            }
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

    private record SubmissionContext(
            M005ReportFixture fixture,
            MutableProvider provider,
            FormalReviewSubmissionService service
    ) {
    }

    private record IntegratedContext(
            M003TestFixture fixture,
            MaterialLifecycleService materials,
            InMemoryOwnedFileByteSource bytes,
            String owner,
            String caseId,
            ChecklistSnapshot snapshot,
            CurrentReviewInputProvider provider,
            FormalReviewSubmissionService submission,
            ReviewM0JobDispatcher dispatcher
    ) {
    }
}
