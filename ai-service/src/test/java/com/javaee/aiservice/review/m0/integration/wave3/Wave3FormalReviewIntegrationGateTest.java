package com.javaee.aiservice.review.m0.integration.wave3;

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
import com.javaee.aiservice.review.m0.material.AssignmentChange;
import com.javaee.aiservice.review.m0.material.JdbcReviewM0MaterialRepository;
import com.javaee.aiservice.review.m0.material.MaterialAttachmentResult;
import com.javaee.aiservice.review.m0.material.MaterialLifecycleService;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.EvidenceRef;
import com.javaee.aiservice.review.m0.model.EvidenceSide;
import com.javaee.aiservice.review.m0.model.FindingDecision;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewMode;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;
import com.javaee.aiservice.review.m0.report.EngineFreshnessService;
import com.javaee.aiservice.review.m0.report.FindingDecisionService;
import com.javaee.aiservice.review.m0.report.JdbcReviewReportReader;
import com.javaee.aiservice.review.m0.report.PublishedReview;
import com.javaee.aiservice.review.m0.report.ReviewReportAssembler;
import com.javaee.aiservice.review.m0.review.BoundedRegexMatcher;
import com.javaee.aiservice.review.m0.review.CapturedReviewInput;
import com.javaee.aiservice.review.m0.review.CurrentReviewInputProvider;
import com.javaee.aiservice.review.m0.review.DeterministicReviewEngine;
import com.javaee.aiservice.review.m0.review.FormalReviewJobHandler;
import com.javaee.aiservice.review.m0.review.FormalReviewRequest;
import com.javaee.aiservice.review.m0.review.FormalReviewSubmissionService;
import com.javaee.aiservice.review.m0.review.IncrementalReviewJobHandler;
import com.javaee.aiservice.review.m0.review.IncrementalReviewValidator;
import com.javaee.aiservice.review.m0.review.ReviewJobRequestEnvelope;
import com.javaee.aiservice.review.m0.review.ReviewM0ReviewJobExecutor;
import com.javaee.aiservice.review.m0.store.JdbcReviewReportStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent persistence, owner, publication, idempotency, and Job integration gate. */
class Wave3FormalReviewIntegrationGateTest {
    private static final String OWNER = "wave3-owner";
    private static final String OTHER_OWNER = "wave3-other-owner";

    @Test
    void candidateStaleMissingOldUploadAndCrossOwnerRequestsLeaveNoFormalSideEffects() {
        ReviewContext candidate = context("wave3-candidate", false);
        assertRejectedWithoutFormalSideEffects(
                candidate,
                OWNER,
                new FormalReviewRequest(
                        ReviewMode.FULL, "candidate-snapshot", "candidate-upload", null, List.of()),
                ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED);

        ReviewContext missingUpload = context("wave3-missing-upload", true);
        assertRejectedWithoutFormalSideEffects(
                missingUpload,
                OWNER,
                fullRequest(missingUpload, "missing-upload"),
                ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED);

        ReviewContext oldUpload = context("wave3-old-upload", true);
        UploadConfirmation old = oldUpload.materials().confirmUploadOwned(
                OWNER, oldUpload.caseId(), oldUpload.snapshot().checklistSnapshotId(), 0, true);
        putPdf(oldUpload, "late-file", "LATE_FILE_BODY");
        oldUpload.materials().attachOwned(OWNER, oldUpload.caseId(), "late-file", "late.pdf");
        assertRejectedWithoutFormalSideEffects(
                oldUpload,
                OWNER,
                fullRequest(oldUpload, old.uploadConfirmationId()),
                ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED);

        ReviewContext stale = context("wave3-stale-basis", true);
        UploadConfirmation staleUpload = stale.materials().confirmUploadOwned(
                OWNER, stale.caseId(), stale.snapshot().checklistSnapshotId(), 0, true);
        ReviewCase beforeBasisChange = stale.fixture().caseStore()
                .findOwned(OWNER, stale.caseId()).orElseThrow();
        stale.fixture().basisService().reclassifyOwned(
                OWNER, stale.caseId(), stale.basisId(), beforeBasisChange.version(), 1,
                com.javaee.aiservice.review.m0.model.OfficialBasisRole.FILLING_GUIDE);
        assertRejectedWithoutFormalSideEffects(
                stale,
                OWNER,
                fullRequest(stale, staleUpload.uploadConfirmationId()),
                ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_STALE_BASIS);

        ReviewContext crossOwner = context("wave3-cross-owner", true);
        UploadConfirmation crossUpload = crossOwner.materials().confirmUploadOwned(
                OWNER, crossOwner.caseId(), crossOwner.snapshot().checklistSnapshotId(), 0, true);
        assertRejectedWithoutFormalSideEffects(
                crossOwner,
                OTHER_OWNER,
                fullRequest(crossOwner, crossUpload.uploadConfirmationId()),
                ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
    }

    @Test
    void readyReportRequiresEveryBlockerToBeZeroAndUsesNoRemoteOrAgentPath() {
        ReviewContext context = context("wave3-ready", true);
        putPdf(context, "ready-file", "READY_FILE_BODY");
        MaterialAttachmentResult attachment = context.materials().attachOwned(
                OWNER, context.caseId(), "ready-file", "ready.pdf");
        SubmissionMaterial material = attachment.materials().get(0);
        context.materials().replaceAssignmentsOwned(
                OWNER, context.caseId(), 0,
                List.of(new AssignmentChange(
                        material.materialId(), context.requirementId(), AssignmentStatus.USER_CONFIRMED)));
        UploadConfirmation upload = context.materials().confirmUploadOwned(
                OWNER, context.caseId(), context.snapshot().checklistSnapshotId(), 1, true);

        ReviewM0JobSubmission submitted = submit(context, "ready-idempotency", fullRequest(
                context, upload.uploadConfirmationId()));
        ReviewJob finished = context.dispatcher().runNext(
                "ready-worker", M003TestFixture.NOW, Duration.ofMinutes(5), 1).orElseThrow();
        PublishedReview published = context.reader().requireOwned(
                OWNER, context.caseId(), finished.result().resultId());

        assertThat(finished.jobId()).isEqualTo(submitted.job().jobId());
        assertThat(finished.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(published.report().freshness()).isEqualTo(ReviewReportFreshness.CURRENT);
        assertThat(published.report().assessment()).isEqualTo(ReviewAssessment.M0_READY);
        assertThat(published.report().missingCount()).isZero();
        assertThat(published.report().fileNameNoncompliantCount()).isZero();
        assertThat(published.report().cannotConfirmCount()).isZero();
        assertThat(published.findings()).isEmpty();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call", Integer.class)).isZero();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_consent", Integer.class)).isZero();
    }

    @Test
    void concurrentSameFingerprintJobsPublishOneReportAndIgnoreStaysAppendOnlyNotReady() throws Exception {
        ReviewContext context = context("wave3-one-report", true);
        UploadConfirmation upload = context.materials().confirmUploadOwned(
                OWNER, context.caseId(), context.snapshot().checklistSnapshotId(), 0, true);
        FormalReviewRequest request = fullRequest(context, upload.uploadConfirmationId());
        ReviewM0JobSubmission first = submit(context, "same-input-a", request);
        ReviewM0JobSubmission second = submit(context, "same-input-b", request);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ReviewJob firstResult;
        ReviewJob secondResult;
        try {
            Future<ReviewJob> a = workers.submit(() -> runSimultaneously(
                    context, "same-worker-a", ready, start));
            Future<ReviewJob> b = workers.submit(() -> runSimultaneously(
                    context, "same-worker-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResult = a.get(20, TimeUnit.SECONDS);
            secondResult = b.get(20, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(firstResult.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(secondResult.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(Set.of(firstResult.jobId(), secondResult.jobId()))
                .containsExactlyInAnyOrder(first.job().jobId(), second.job().jobId());
        assertThat(firstResult.result().resultId()).isEqualTo(secondResult.result().resultId());
        assertThat(count(context, "review_m0_report")).isEqualTo(1);
        assertThat(count(context, "review_m0_finding")).isEqualTo(1);
        assertThat(count(context, "review_m0_evidence_ref")).isGreaterThanOrEqualTo(2);

        String reportId = firstResult.result().resultId();
        String findingId = context.fixture().database().jdbc().queryForObject(
                "SELECT finding_id FROM review_m0_finding WHERE report_id = ?", String.class, reportId);
        ReviewReport immutableBefore = context.reportStore()
                .findOwned(OWNER, context.caseId(), reportId).orElseThrow();
        FindingDecision ignored = context.decisions().ignoreOwned(
                OWNER, context.caseId(), reportId, findingId, "synthetic reason");
        FindingDecision replay = context.decisions().ignoreOwned(
                OWNER, context.caseId(), reportId, findingId, "different replay reason");

        assertThat(replay.decisionId()).isEqualTo(ignored.decisionId());
        assertThat(count(context, "review_m0_finding_decision")).isEqualTo(1);
        assertThat(context.decisions().getEffectiveOwned(OWNER, context.caseId(), reportId).ignoredCount())
                .isEqualTo(1);
        assertThat(context.decisions().getEffectiveOwned(
                OWNER, context.caseId(), reportId).effectiveAssessment())
                .isEqualTo(ReviewAssessment.M0_NOT_READY);
        assertThat(context.reportStore().findOwned(OWNER, context.caseId(), reportId).orElseThrow())
                .isEqualTo(immutableBefore);
        assertThat(context.reader().requireOwned(OWNER, context.caseId(), reportId).findings())
                .hasSize(1).allSatisfy(item -> assertThat(item.evidence()).hasSizeGreaterThanOrEqualTo(2));

        assertThat(context.reader().findOwned(OTHER_OWNER, context.caseId(), reportId)).isEmpty();
        assertThat(context.fixture().jobStore().findOwned(
                OTHER_OWNER, context.caseId(), firstResult.jobId())).isEmpty();
        assertThatThrownBy(() -> context.decisions().ignoreOwned(
                OTHER_OWNER, context.caseId(), reportId, findingId, null))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);

        new EngineFreshnessService(context.fixture().caseStore(), context.reportStore())
                .invalidateDifferentEngineOwned(OWNER, context.caseId(), "next-engine-version");
        assertThat(context.reportStore().findCurrentOwned(OWNER, context.caseId())).isEmpty();
        assertThat(context.reportStore().findOwned(OWNER, context.caseId(), reportId).orElseThrow().freshness())
                .isEqualTo(ReviewReportFreshness.OUTDATED);
        assertThat(context.decisions().ignoreOwned(
                OWNER, context.caseId(), reportId, findingId, null).decisionId())
                .isEqualTo(ignored.decisionId());
    }

    @ParameterizedTest
    @EnumSource(EvidenceSide.class)
    void missingEitherEvidenceSideRejectsPublicationBeforePersistenceOrPseudoSuccess(
            EvidenceSide omittedSide
    ) {
        ReviewContext context = context("wave3-evidence-" + omittedSide, true);
        UploadConfirmation upload = context.materials().confirmUploadOwned(
                OWNER, context.caseId(), context.snapshot().checklistSnapshotId(), 0, true);
        ReviewM0JobSubmission submitted = submit(
                context, "evidence-" + omittedSide, fullRequest(context, upload.uploadConfirmationId()));
        ReviewJob leased = context.fixture().jobStore().acquireNext(
                "evidence-worker", M003TestFixture.NOW, Duration.ofMinutes(5)).orElseThrow();
        ReviewJobRequestEnvelope envelope = ReviewJobRequestEnvelope.readCanonical(leased.requestJson());
        CapturedReviewInput input = context.provider().captureForExecution(
                OWNER, context.caseId(), context.snapshot().checklistSnapshotId(),
                upload.uploadConfirmationId());
        ReportBundle valid = context.assembler().assemble(
                leased, envelope, context.engine().evaluateFull(input));
        List<EvidenceRef> incomplete = valid.evidence().stream()
                .filter(item -> item.evidenceSide() != omittedSide)
                .toList();

        assertThatThrownBy(() -> context.reportStore().publishOwned(
                OWNER, leased.leaseOwner(), M003TestFixture.NOW, new ReportBundle(
                        valid.report(), valid.findings(), incomplete)))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT);
        assertThat(count(context, "review_m0_report")).isZero();
        assertThat(count(context, "review_m0_finding")).isZero();
        assertThat(count(context, "review_m0_evidence_ref")).isZero();
        assertThat(context.fixture().jobStore().findOwned(
                OWNER, context.caseId(), submitted.job().jobId()).orElseThrow().status())
                .isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(context.fixture().caseStore().findOwned(
                OWNER, context.caseId()).orElseThrow().currentReportId()).isNull();
    }

    @Test
    void sameIdempotencyKeyAndSameHttpPayloadStillReplaysAfterLogicalNameRevision() {
        ReviewContext context = context("wave3-idempotency-replay", true);
        putPdf(context, "idempotent-file", "IDEMPOTENT_FILE_BODY");
        MaterialAttachmentResult attachment = context.materials().attachOwned(
                OWNER, context.caseId(), "idempotent-file", "before.pdf");
        UploadConfirmation upload = context.materials().confirmUploadOwned(
                OWNER, context.caseId(), context.snapshot().checklistSnapshotId(), 1, true);
        FormalReviewRequest sameHttpPayload = fullRequest(context, upload.uploadConfirmationId());
        IdempotencyScope sameKey = scope(context, "stable-http-key");
        ReviewM0JobSubmission original = context.submission().submitOwned(
                sameKey, context.caseId(), sameHttpPayload);

        SubmissionMaterial material = attachment.materials().get(0);
        context.materials().renameOwned(
                OWNER, context.caseId(), material.materialId(), material.version(), "after.pdf");
        ReviewM0JobSubmission replay = context.submission().submitOwned(
                sameKey, context.caseId(), sameHttpPayload);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.job().jobId()).isEqualTo(original.job().jobId());
        assertThat(count(context, "review_m0_job")).isEqualTo(1);
        assertThat(count(context, "review_m0_idempotency")).isEqualTo(1);
    }

    private static ReviewContext context(String caseId, boolean publish) {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase(caseId, OWNER, ProcessingMode.LOCAL_ONLY);
        String basisId = "basis-" + caseId;
        fixture.attachReadyBasis(OWNER, caseId, 0, basisId);
        ChecklistDraftView draft = fixture.checklistService().replaceOfficialCandidatesOwned(
                OWNER, caseId, 1, null,
                List.of(fixture.officialCandidate(basisId, "Required material")));
        ChecklistSnapshot snapshot = null;
        if (publish) {
            ReviewCase current = fixture.caseStore().findOwned(OWNER, caseId).orElseThrow();
            snapshot = fixture.checklistService().publishOwned(
                    OWNER, caseId, current.version(), draft.revision(),
                    Set.of(draft.items().get(0).requirementId()));
        }

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
                fixture.caseStore(), fixture.checklistService(), fixture.checklistStore(), materials,
                DeterministicReviewEngine.ENGINE_VERSION);
        JdbcReviewReportStore reports = new JdbcReviewReportStore(
                fixture.database().jdbc(), fixture.jobStore(), fixture.database().transactions());
        JdbcReviewReportReader reader = new JdbcReviewReportReader(
                fixture.database().jdbc(), reports, fixture.jobStore());
        IncrementalReviewValidator incremental = new IncrementalReviewValidator();
        ReviewM0JobSubmissionService jobSubmission = new ReviewM0JobSubmissionService(
                new JdbcReviewM0IdempotencyStore(
                        fixture.database().jdbc(), fixture.database().transactions()),
                fixture.jobStore(), fixture.database().transactions());
        FormalReviewSubmissionService submission = new FormalReviewSubmissionService(
                fixture.database().jdbc(), fixture.database().transactions(), provider, reader,
                incremental, jobSubmission, M003TestFixture.CLOCK);
        DeterministicReviewEngine engine = new DeterministicReviewEngine(
                new BoundedRegexMatcher(Duration.ofMillis(250)), new ReviewEvidenceFactory());
        ReviewReportAssembler assembler = new ReviewReportAssembler(M003TestFixture.CLOCK);
        ReviewM0ReviewJobExecutor executor = new ReviewM0ReviewJobExecutor(
                provider, reader, incremental, engine, assembler, reports, M003TestFixture.CLOCK);
        ReviewM0JobHandlerRegistry handlers = new ReviewM0JobHandlerRegistry(List.of(
                new FormalReviewJobHandler(executor), new IncrementalReviewJobHandler(executor)));
        ReviewM0JobDispatcher dispatcher = new ReviewM0JobDispatcher(fixture.jobStore(), handlers);
        FindingDecisionService decisions = new FindingDecisionService(
                fixture.database().jdbc(), fixture.database().transactions(), reader, M003TestFixture.CLOCK);
        return new ReviewContext(
                fixture, materials, bytes, caseId, basisId, draft.items().get(0).requirementId(),
                snapshot, provider, reports, reader, submission, engine, assembler, dispatcher, decisions);
    }

    private static ReviewM0JobSubmission submit(
            ReviewContext context, String idempotencyKey, FormalReviewRequest request
    ) {
        return context.submission().submitOwned(
                scope(context, idempotencyKey), context.caseId(), request);
    }

    private static IdempotencyScope scope(ReviewContext context, String idempotencyKey) {
        return new IdempotencyScope(
                OWNER, "POST", "/api/review-m0/cases/" + context.caseId() + "/reviews",
                idempotencyKey);
    }

    private static FormalReviewRequest fullRequest(ReviewContext context, String uploadId) {
        return new FormalReviewRequest(
                ReviewMode.FULL, context.snapshot().checklistSnapshotId(), uploadId, null, List.of());
    }

    private static void assertRejectedWithoutFormalSideEffects(
            ReviewContext context,
            String owner,
            FormalReviewRequest request,
            ReviewM0ErrorCode expected
    ) {
        assertThatThrownBy(() -> context.submission().submitOwned(
                new IdempotencyScope(
                        owner, "POST", "/api/review-m0/cases/" + context.caseId() + "/reviews",
                        "rejected-" + expected),
                context.caseId(), request))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(expected);
        assertThat(count(context, "review_m0_job")).isZero();
        assertThat(count(context, "review_m0_report")).isZero();
        assertThat(count(context, "review_m0_finding")).isZero();
        assertThat(count(context, "review_m0_evidence_ref")).isZero();
        assertThat(context.fixture().caseStore().findOwned(
                OWNER, context.caseId()).orElseThrow().currentReportId()).isNull();
    }

    private static ReviewJob runSimultaneously(
            ReviewContext context,
            String worker,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Wave 3 concurrent start barrier timed out");
        }
        return context.dispatcher().runNext(
                worker, M003TestFixture.NOW, Duration.ofMinutes(5), 1).orElseThrow();
    }

    private static void putPdf(ReviewContext context, String fileId, String marker) {
        context.bytes().put(
                OWNER, fileId, fileId + ".pdf", "application/pdf",
                SyntheticFileFixtures.pdf(marker), "storage-v1", "synthetic/object/key/" + fileId);
    }

    private static int count(ReviewContext context, String table) {
        if (!Set.of(
                "review_m0_job", "review_m0_idempotency", "review_m0_report",
                "review_m0_finding", "review_m0_evidence_ref", "review_m0_finding_decision")
                .contains(table)) {
            throw new IllegalArgumentException("Unexpected Wave 3 table");
        }
        return context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record ReviewContext(
            M003TestFixture fixture,
            MaterialLifecycleService materials,
            InMemoryOwnedFileByteSource bytes,
            String caseId,
            String basisId,
            String requirementId,
            ChecklistSnapshot snapshot,
            CurrentReviewInputProvider provider,
            JdbcReviewReportStore reportStore,
            JdbcReviewReportReader reader,
            FormalReviewSubmissionService submission,
            DeterministicReviewEngine engine,
            ReviewReportAssembler assembler,
            ReviewM0JobDispatcher dispatcher,
            FindingDecisionService decisions
    ) {
    }
}
