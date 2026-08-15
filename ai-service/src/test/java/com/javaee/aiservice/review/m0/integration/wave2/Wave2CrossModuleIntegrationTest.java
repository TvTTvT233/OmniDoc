package com.javaee.aiservice.review.m0.integration.wave2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.archive.ZipArchiveEntryOpener;
import com.javaee.aiservice.review.m0.archive.ZipArchiveInspector;
import com.javaee.aiservice.review.m0.checklist.ChecklistDraftView;
import com.javaee.aiservice.review.m0.checklist.ChecklistItemEdit;
import com.javaee.aiservice.review.m0.checklist.M003TestFixture;
import com.javaee.aiservice.review.m0.extraction.ChecklistExtractionJobHandler;
import com.javaee.aiservice.review.m0.extraction.ChecklistExtractionRequest;
import com.javaee.aiservice.review.m0.extraction.OfficialBasisSegment;
import com.javaee.aiservice.review.m0.file.FormalContentInspector;
import com.javaee.aiservice.review.m0.file.InMemoryOwnedFileByteSource;
import com.javaee.aiservice.review.m0.file.SyntheticFileFixtures;
import com.javaee.aiservice.review.m0.file.VerifiedReviewM0FileAccess;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.JdbcReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.job.ReviewM0JobDispatcher;
import com.javaee.aiservice.review.m0.job.ReviewM0JobHandlerRegistry;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmissionService;
import com.javaee.aiservice.review.m0.material.JdbcReviewM0MaterialRepository;
import com.javaee.aiservice.review.m0.material.MaterialCollectionSnapshot;
import com.javaee.aiservice.review.m0.material.MaterialLifecycleService;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.OfficialBasis;
import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.OfficialBasisSourceLocator;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewSegment;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewService;
import com.javaee.aiservice.review.m0.store.JdbcReviewReportStore;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.javaee.aiservice.review.m0.file.SyntheticFileFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Wave2CrossModuleIntegrationTest {
    private static final String OWNER = "wave2-owner";
    private static final String OTHER_OWNER = "wave2-other";
    private static final String FINGERPRINT = "f".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void basisChecklistAndMaterialChangesAdvanceOnlyTheirRevisionAndOutdateThePriorReport() {
        PublishedContext material = publishedContext("wave2-material-revision");
        putPdf(material, "material-file", "MATERIAL_REVISION_BODY");
        material.materials().attachOwned(OWNER, material.caseId(), "material-file", "material.pdf");

        ReviewCase afterMaterial = material.fixture().caseStore().findOwned(OWNER, material.caseId()).orElseThrow();
        assertThat(afterMaterial.basisRevision()).isEqualTo(1);
        assertThat(afterMaterial.checklistRevision()).isEqualTo(material.draft().revision());
        assertThat(afterMaterial.materialSetRevision()).isEqualTo(1);
        assertOutdated(material);

        PublishedContext checklist = publishedContext("wave2-checklist-revision");
        ChecklistDraftView edited = checklist.fixture().checklistService().editRequirementOwned(
                OWNER, checklist.caseId(), checklist.draft().items().get(0).requirementId(),
                new ChecklistItemEdit(
                        0, "User-confirmed revised requirement", true, RequirementApplicability.APPLIES,
                        1, new FileNameRule(FileNameRuleKind.NONE, null, null)));
        ReviewCase beforeChecklistPublish = checklist.fixture().caseStore()
                .findOwned(OWNER, checklist.caseId()).orElseThrow();
        ChecklistSnapshot replacement = checklist.fixture().checklistService().publishOwned(
                OWNER, checklist.caseId(), beforeChecklistPublish.version(), edited.revision(),
                Set.of(edited.items().get(0).requirementId()));

        ReviewCase afterChecklist = checklist.fixture().caseStore()
                .findOwned(OWNER, checklist.caseId()).orElseThrow();
        assertThat(afterChecklist.basisRevision()).isEqualTo(1);
        assertThat(afterChecklist.checklistRevision()).isEqualTo(edited.revision());
        assertThat(afterChecklist.materialSetRevision()).isZero();
        assertThat(afterChecklist.currentChecklistSnapshotId()).isEqualTo(replacement.checklistSnapshotId());
        assertThat(checklist.fixture().caseStore().findChecklistSnapshotOwned(
                OWNER, checklist.caseId(), checklist.snapshot().checklistSnapshotId()).orElseThrow().lifecycle())
                .isEqualTo(ChecklistLifecycle.SUPERSEDED);
        assertOutdated(checklist);

        PublishedContext basis = publishedContext("wave2-basis-revision");
        ReviewCase beforeBasisChange = basis.fixture().caseStore().findOwned(OWNER, basis.caseId()).orElseThrow();
        basis.fixture().basisService().reclassifyOwned(
                OWNER, basis.caseId(), basis.basis().basisId(), beforeBasisChange.version(),
                basis.basis().version(), OfficialBasisRole.FILLING_GUIDE);

        ReviewCase afterBasis = basis.fixture().caseStore().findOwned(OWNER, basis.caseId()).orElseThrow();
        assertThat(afterBasis.basisRevision()).isEqualTo(2);
        assertThat(afterBasis.checklistRevision()).isEqualTo(basis.draft().revision());
        assertThat(afterBasis.materialSetRevision()).isZero();
        assertThat(basis.fixture().caseStore().findChecklistSnapshotOwned(
                OWNER, basis.caseId(), basis.snapshot().checklistSnapshotId()).orElseThrow().lifecycle())
                .isEqualTo(ChecklistLifecycle.STALE_BASIS);
        assertOutdated(basis);
    }

    @Test
    void ownerCanReadChecklistAndMaterialSnapshotsConsistentlyWhileCrossOwnerPathsAreAbsent() {
        ChecklistContext context = checklistContext("wave2-owner-read", ProcessingMode.LOCAL_ONLY, true);
        putPdf(context, "owner-file", "OWNER_SCOPED_BODY");
        context.materials().attachOwned(OWNER, context.caseId(), "owner-file", "owner.pdf");

        ChecklistSnapshot checklist = context.fixture().checklistService().requireCurrentFormalSnapshotOwned(
                OWNER, context.caseId(), context.snapshot().checklistSnapshotId());
        MaterialCollectionSnapshot materials = context.materials().getSnapshotOwned(OWNER, context.caseId());
        ReviewCase reviewCase = context.fixture().caseStore().findOwned(OWNER, context.caseId()).orElseThrow();

        assertThat(checklist.caseId()).isEqualTo(materials.caseId()).isEqualTo(reviewCase.caseId());
        assertThat(checklist.checklistSnapshotId()).isEqualTo(reviewCase.currentChecklistSnapshotId());
        assertThat(materials.materialSetRevision()).isEqualTo(reviewCase.materialSetRevision());
        assertThat(materials.materials()).hasSize(1);
        assertThat(context.fixture().caseStore().findChecklistSnapshotOwned(
                OTHER_OWNER, context.caseId(), checklist.checklistSnapshotId())).isEmpty();
        assertThatThrownBy(() -> context.fixture().checklistService().requireCurrentFormalSnapshotOwned(
                OTHER_OWNER, context.caseId(), checklist.checklistSnapshotId()))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
        assertThatThrownBy(() -> context.materials().getSnapshotOwned(OTHER_OWNER, context.caseId()))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
    }

    @Test
    void checklistPublicationAndQueuedInfrastructureNeverTreatUnconfirmedUploadAsReviewReady() {
        ChecklistContext context = checklistContext("wave2-upload-gate", ProcessingMode.LOCAL_ONLY, true);
        putPdf(context, "unconfirmed-file", "UNCONFIRMED_BODY");
        context.materials().attachOwned(OWNER, context.caseId(), "unconfirmed-file", "unconfirmed.pdf");

        ReviewJob queued = queuedJob(
                "wave2-unconfirmed-job", context.caseId(), ReviewJobType.CHECKLIST_EXTRACTION,
                "{}", null, "wave2-unconfirmed-idem");
        ReviewM0JobSubmissionService submission = submissionService(context.fixture());
        submission.submit(
                new IdempotencyScope(OWNER, "POST", "/api/review-m0/cases/" + context.caseId()
                        + "/checklist-extractions", "wave2-unconfirmed-idem"),
                queued.requestDigest(), queued);

        assertThatThrownBy(() -> context.materials().requireReviewReadyOwned(OWNER, context.caseId()))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED);
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_upload_confirmation", Integer.class)).isZero();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_finding", Integer.class)).isZero();
        assertThat(context.fixture().caseStore().findOwned(OWNER, context.caseId()).orElseThrow().currentReportId())
                .isNull();
    }

    @Test
    void materialBodiesZipEntryBodiesAndRedactedPreviewDoNotCrossIntoAuditLogsOrExceptions() {
        String marker = "WAVE2_PRIVATE_MARKER_73519";
        ChecklistContext context = checklistContext("wave2-sensitive", ProcessingMode.REMOTE_MODEL, true);
        putPdf(context, "private-pdf", marker);
        context.byteSource().put(
                OWNER, "private-zip", "private.zip", "application/zip",
                SyntheticFileFixtures.zip(List.of(entry("inside.pdf", SyntheticFileFixtures.pdf(marker)))),
                "storage-v1", "synthetic/object/key");

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        List<String> exceptionMessages = new ArrayList<>();
        try {
            context.materials().attachOwned(OWNER, context.caseId(), "private-pdf", "private.pdf");
            context.materials().attachOwned(OWNER, context.caseId(), "private-zip", null);
            RemotePreviewService previews = new RemotePreviewService(
                    context.fixture().caseStore(), context.fixture().checklistStore(),
                    context.fixture().privacyStore(), M003TestFixture.CLOCK);
            RemotePreviewSegment segment = new RemotePreviewSegment(
                    "segment-private", context.basis().basisId(),
                    new OfficialBasisSourceLocator(null, 1, null, null, "block-private", null),
                    true, marker);
            previews.createOwned(
                    OWNER, context.caseId(), ReviewJobType.CHECKLIST_EXTRACTION, 1, List.of(segment));

            captureMessage(exceptionMessages, () -> context.materials().getSnapshotOwned(
                    OTHER_OWNER, context.caseId()));
            captureMessage(exceptionMessages, () -> context.fixture().checklistService()
                    .requireCurrentFormalSnapshotOwned(
                            OTHER_OWNER, context.caseId(), context.snapshot().checklistSnapshotId()));
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }

        assertThat(exceptionMessages).allSatisfy(message -> assertThat(message).doesNotContain(marker));
        assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(marker));
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_job WHERE request_json LIKE ? OR error_message LIKE ?",
                Integer.class, "%" + marker + "%", "%" + marker + "%")).isZero();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_job_checkpoint WHERE checkpoint_json LIKE ?",
                Integer.class, "%" + marker + "%")).isZero();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call WHERE provider_request_id LIKE ?",
                Integer.class, "%" + marker + "%")).isZero();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_input_diagnostic WHERE locator_json LIKE ?",
                Integer.class, "%" + marker + "%")).isZero();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_report", Integer.class)).isZero();
    }

    @Test
    void localOnlyMaterialProcessingAndChecklistExtractionNeverAuthorizeOrSendRemotePayloads() {
        ChecklistContext context = checklistContext("wave2-local-only", ProcessingMode.LOCAL_ONLY, false);
        putPdf(context, "local-material", "LOCAL_ONLY_MATERIAL_BODY");
        context.materials().attachOwned(OWNER, context.caseId(), "local-material", "local.pdf");

        AtomicInteger remoteConstructions = new AtomicInteger();
        AtomicInteger remoteCalls = new AtomicInteger();
        ChecklistExtractionJobHandler handler = new ChecklistExtractionJobHandler(
                context.fixture().caseStore(), context.fixture().checklistService(),
                (owner, caseId, revision) -> List.of(new OfficialBasisSegment(
                        context.basis().basisId(),
                        new OfficialBasisSourceLocator(null, 1, null, null, "block-local", null),
                        "Synthetic local-only official basis")),
                segments -> List.of(context.fixture().officialCandidate(
                        context.basis().basisId(), "Local-only candidate")),
                context.fixture().consentVerifier(), context.fixture().remoteCallStore(),
                () -> {
                    remoteConstructions.incrementAndGet();
                    return payload -> {
                        remoteCalls.incrementAndGet();
                        throw new AssertionError("LOCAL_ONLY must not call a remote client");
                    };
                }, M003TestFixture.CLOCK);
        ChecklistExtractionRequest request = new ChecklistExtractionRequest(1, ProcessingMode.LOCAL_ONLY, null);
        ReviewJob job = queuedJob(
                "wave2-local-job", context.caseId(), ReviewJobType.CHECKLIST_EXTRACTION,
                request.canonicalJson(), null, "wave2-local-idem");
        context.fixture().jobStore().create(job);

        ReviewJob result = new ReviewM0JobDispatcher(
                context.fixture().jobStore(), new ReviewM0JobHandlerRegistry(List.of(handler)))
                .runNext("wave2-local-worker", M003TestFixture.NOW, Duration.ofMinutes(5), 1)
                .orElseThrow();

        assertThat(result.status()).isEqualTo(ReviewJobStatus.SUCCEEDED);
        assertThat(remoteConstructions).hasValue(0);
        assertThat(remoteCalls).hasValue(0);
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_consent", Integer.class)).isZero();
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_remote_call", Integer.class)).isZero();
        assertThat(context.materials().getSnapshotOwned(OWNER, context.caseId()).materials()).hasSize(1);
        assertThat(context.fixture().checklistService().requireLatestDraftOwned(OWNER, context.caseId()).lifecycle())
                .isEqualTo(ChecklistLifecycle.CANDIDATE_DRAFT);
    }

    @Test
    void concurrentChecklistPublishMaterialRevisionAndJobSubmitLeaveNoLostOrHalfCommittedState() throws Exception {
        ChecklistContext context = checklistContext("wave2-concurrent", ProcessingMode.LOCAL_ONLY, false);
        putPdf(context, "concurrent-file", "CONCURRENT_BODY");
        ReviewCase before = context.fixture().caseStore().findOwned(OWNER, context.caseId()).orElseThrow();
        ReviewJob job = queuedJob(
                "wave2-concurrent-job", context.caseId(), ReviewJobType.CHECKLIST_EXTRACTION,
                "{}", null, "wave2-concurrent-idem");
        ReviewM0JobSubmissionService submission = submissionService(context.fixture());

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<OperationResult> checklist = workers.submit(simultaneous(ready, start, () ->
                    context.fixture().checklistService().publishOwned(
                            OWNER, context.caseId(), before.version(), context.draft().revision(),
                            Set.of(context.draft().items().get(0).requirementId()))));
            Future<OperationResult> material = workers.submit(simultaneous(ready, start, () ->
                    context.materials().attachOwned(
                            OWNER, context.caseId(), "concurrent-file", "concurrent.pdf")));
            Future<OperationResult> jobSubmit = workers.submit(simultaneous(ready, start, () ->
                    submission.submit(
                            new IdempotencyScope(
                                    OWNER, "POST", "/api/review-m0/cases/" + context.caseId()
                                            + "/checklist-extractions", "wave2-concurrent-idem"),
                            job.requestDigest(), job)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            OperationResult checklistResult = checklist.get(20, TimeUnit.SECONDS);
            OperationResult materialResult = material.get(20, TimeUnit.SECONDS);
            OperationResult jobResult = jobSubmit.get(20, TimeUnit.SECONDS);
            assertThat(jobResult).isEqualTo(OperationResult.SUCCESS);
            assertThat(checklistResult).isIn(OperationResult.SUCCESS, OperationResult.VERSION_CONFLICT);
            assertThat(materialResult).isIn(OperationResult.SUCCESS, OperationResult.VERSION_CONFLICT);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        int snapshots = count(context, "review_m0_checklist_snapshot");
        int sources = count(context, "review_m0_submission_source");
        int materials = count(context, "review_m0_material");
        ReviewCase after = context.fixture().caseStore().findOwned(OWNER, context.caseId()).orElseThrow();

        assertThat(snapshots).isBetween(0, 1);
        assertThat(sources).isBetween(0, 1).isEqualTo(materials);
        assertThat(snapshots + sources).isGreaterThanOrEqualTo(1);
        assertThat(after.basisRevision()).isEqualTo(1);
        assertThat(after.checklistRevision()).isEqualTo(snapshots == 1 ? context.draft().revision() : 0);
        assertThat(after.materialSetRevision()).isEqualTo(sources);
        assertThat(after.version()).isEqualTo(before.version() + snapshots + sources);
        assertThat(count(context, "review_m0_job")).isEqualTo(1);
        assertThat(count(context, "review_m0_idempotency")).isEqualTo(1);
        assertThat(context.fixture().database().jdbc().queryForObject(
                "SELECT state FROM review_m0_idempotency WHERE idempotency_key=?",
                String.class, "wave2-concurrent-idem")).isEqualTo("COMPLETED");
        assertThat(count(context, "review_m0_report")).isZero();
        assertThat(count(context, "review_m0_finding")).isZero();
    }

    private static PublishedContext publishedContext(String caseId) {
        ChecklistContext base = checklistContext(caseId, ProcessingMode.LOCAL_ONLY, true);
        UploadConfirmation confirmation = base.materials().confirmUploadOwned(
                OWNER, caseId, base.snapshot().checklistSnapshotId(), 0, true);
        ReviewCase current = base.fixture().caseStore().findOwned(OWNER, caseId).orElseThrow();
        ReviewJob queued = queuedJob(
                "job-" + caseId, caseId, ReviewJobType.FORMAL_REVIEW,
                "{}", FINGERPRINT, "report-idem-" + caseId);
        base.fixture().jobStore().create(queued);
        ReviewJob leased = base.fixture().jobStore().acquireNext(
                "report-worker", M003TestFixture.NOW, Duration.ofMinutes(5)).orElseThrow();
        ReviewReport report = new ReviewReport(
                "report-" + caseId, caseId, leased.jobId(), "REVIEW_M0_CONTRACT_V1", "wave2-engine",
                ReviewReportFreshness.CURRENT, ReviewAssessment.M0_READY, FINGERPRINT,
                base.snapshot().checklistSnapshotId(), confirmation.uploadConfirmationId(),
                current.basisRevision(), current.materialSetRevision(), current.assignmentRevision(),
                current.nameRevision(), null, 0, 0, 0,
                M003TestFixture.NOW.plusSeconds(1), null, null);
        JdbcReviewReportStore reports = new JdbcReviewReportStore(
                base.fixture().database().jdbc(), base.fixture().jobStore(),
                base.fixture().database().transactions());
        reports.publishOwned(
                OWNER, "report-worker", M003TestFixture.NOW.plusSeconds(2),
                new ReportBundle(report, List.of(), List.of()));
        return new PublishedContext(
                base.fixture(), base.materials(), base.repository(), base.byteSource(), caseId,
                base.basis(), base.draft(), base.snapshot(), reports, report);
    }

    private static ChecklistContext checklistContext(
            String caseId, ProcessingMode mode, boolean publishChecklist
    ) {
        M003TestFixture fixture = new M003TestFixture();
        fixture.createCase(caseId, OWNER, mode);
        OfficialBasis basis = fixture.attachReadyBasis(OWNER, caseId, 0, "basis-" + caseId);
        ChecklistDraftView draft = fixture.checklistService().replaceOfficialCandidatesOwned(
                OWNER, caseId, 1, null,
                List.of(fixture.officialCandidate(basis.basisId(), "Required material")));
        ChecklistSnapshot snapshot = null;
        if (publishChecklist) {
            ReviewCase current = fixture.caseStore().findOwned(OWNER, caseId).orElseThrow();
            snapshot = fixture.checklistService().publishOwned(
                    OWNER, caseId, current.version(), draft.revision(),
                    Set.of(draft.items().get(0).requirementId()));
        }
        JdbcReviewM0MaterialRepository repository = new JdbcReviewM0MaterialRepository(fixture.database().jdbc());
        InMemoryOwnedFileByteSource byteSource = new InMemoryOwnedFileByteSource();
        ArchiveSafetyLimits limits = ArchiveSafetyLimits.secureDefaults();
        FormalContentInspector contentInspector = new FormalContentInspector(limits);
        MaterialLifecycleService materials = new MaterialLifecycleService(
                repository, fixture.caseStore(),
                new VerifiedReviewM0FileAccess(byteSource, new ZipArchiveEntryOpener(limits)),
                new ZipArchiveInspector(limits, contentInspector), contentInspector,
                fixture.database().transactions());
        return new ChecklistContext(
                fixture, materials, repository, byteSource, caseId, basis, draft, snapshot);
    }

    private static ReviewM0JobSubmissionService submissionService(M003TestFixture fixture) {
        return new ReviewM0JobSubmissionService(
                new JdbcReviewM0IdempotencyStore(
                        fixture.database().jdbc(), fixture.database().transactions()),
                fixture.jobStore(), fixture.database().transactions());
    }

    private static ReviewJob queuedJob(
            String jobId,
            String caseId,
            ReviewJobType type,
            String requestJson,
            String inputFingerprint,
            String idempotencyKey
    ) {
        return new ReviewJob(
                jobId, caseId, OWNER, type, ReviewJobStatus.QUEUED,
                requestJson, requestDigest(requestJson), inputFingerprint, idempotencyKey,
                0, 1, 0, null, null, null, null, null, 0,
                M003TestFixture.NOW, null, null, null);
    }

    private static String requestDigest(String json) {
        try {
            return CanonicalJson.sha256(JSON.readValue(json, Object.class));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void putPdf(ChecklistContext context, String fileId, String marker) {
        context.byteSource().put(
                OWNER, fileId, fileId + ".pdf", "application/pdf",
                SyntheticFileFixtures.pdf(marker), "storage-v1", "synthetic/object/key/" + fileId);
    }

    private static void putPdf(PublishedContext context, String fileId, String marker) {
        context.byteSource().put(
                OWNER, fileId, fileId + ".pdf", "application/pdf",
                SyntheticFileFixtures.pdf(marker), "storage-v1", "synthetic/object/key/" + fileId);
    }

    private static void assertOutdated(PublishedContext context) {
        assertThat(context.reports().findOwned(
                OWNER, context.caseId(), context.report().reportId()).orElseThrow().freshness())
                .isEqualTo(ReviewReportFreshness.OUTDATED);
        assertThat(context.fixture().caseStore().findOwned(OWNER, context.caseId()).orElseThrow().currentReportId())
                .isNull();
    }

    private static void captureMessage(List<String> messages, Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected owner-isolated operation to fail");
        } catch (ReviewM0Exception error) {
            messages.add(error.getMessage());
        }
    }

    private static Callable<OperationResult> simultaneous(
            CountDownLatch ready, CountDownLatch start, Callable<?> operation
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent Wave 2 start barrier timed out");
            }
            try {
                operation.call();
                return OperationResult.SUCCESS;
            } catch (ReviewM0Exception error) {
                if (error.errorCode() == ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT) {
                    return OperationResult.VERSION_CONFLICT;
                }
                throw error;
            }
        };
    }

    private static int count(ChecklistContext context, String table) {
        if (!Set.of(
                "review_m0_checklist_snapshot", "review_m0_submission_source", "review_m0_material",
                "review_m0_job", "review_m0_idempotency", "review_m0_report", "review_m0_finding")
                .contains(table)) {
            throw new IllegalArgumentException("Unexpected Wave 2 table");
        }
        return context.fixture().database().jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private enum OperationResult { SUCCESS, VERSION_CONFLICT }

    private record ChecklistContext(
            M003TestFixture fixture,
            MaterialLifecycleService materials,
            JdbcReviewM0MaterialRepository repository,
            InMemoryOwnedFileByteSource byteSource,
            String caseId,
            OfficialBasis basis,
            ChecklistDraftView draft,
            ChecklistSnapshot snapshot
    ) {
    }

    private record PublishedContext(
            M003TestFixture fixture,
            MaterialLifecycleService materials,
            JdbcReviewM0MaterialRepository repository,
            InMemoryOwnedFileByteSource byteSource,
            String caseId,
            OfficialBasis basis,
            ChecklistDraftView draft,
            ChecklistSnapshot snapshot,
            JdbcReviewReportStore reports,
            ReviewReport report
    ) {
    }
}
