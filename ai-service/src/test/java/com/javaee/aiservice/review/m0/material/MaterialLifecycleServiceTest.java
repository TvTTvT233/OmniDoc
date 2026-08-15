package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.archive.ZipArchiveEntryOpener;
import com.javaee.aiservice.review.m0.archive.ZipArchiveInspector;
import com.javaee.aiservice.review.m0.file.FormalContentInspector;
import com.javaee.aiservice.review.m0.file.InMemoryOwnedFileByteSource;
import com.javaee.aiservice.review.m0.file.SyntheticFileFixtures;
import com.javaee.aiservice.review.m0.file.VerifiedReviewM0FileAccess;
import com.javaee.aiservice.review.m0.job.JdbcReviewM0JobStore;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;
import com.javaee.aiservice.review.m0.store.JdbcReviewCaseStore;
import com.javaee.aiservice.review.m0.store.JdbcReviewReportStore;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import com.javaee.aiservice.review.m0.store.ReviewM0JdbcTestFixture;
import com.javaee.aiservice.review.m0.store.ReviewM0StoreTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.javaee.aiservice.review.m0.file.SyntheticFileFixtures.entry;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialLifecycleServiceTest {
    private static final String OWNER = "owner-1";
    private static final String CASE_ID = "case-1";
    private static final String REQUIREMENT_ID = "requirement-1";

    private ReviewM0JdbcTestFixture fixture;
    private JdbcReviewCaseStore caseStore;
    private JdbcReviewM0MaterialRepository repository;
    private InMemoryOwnedFileByteSource byteSource;
    private VerifiedReviewM0FileAccess fileAccess;
    private ArchiveSafetyLimits limits;
    private FormalContentInspector contentInspector;
    private ZipArchiveInspector archiveInspector;
    private MaterialLifecycleService service;
    private MaterialDownloadService downloads;

    @BeforeEach
    void setUp() {
        fixture = new ReviewM0JdbcTestFixture();
        caseStore = new JdbcReviewCaseStore(fixture.jdbc(), fixture.transactions());
        repository = new JdbcReviewM0MaterialRepository(fixture.jdbc());
        byteSource = new InMemoryOwnedFileByteSource();
        limits = ArchiveSafetyLimits.secureDefaults();
        contentInspector = new FormalContentInspector(limits);
        archiveInspector = new ZipArchiveInspector(limits, contentInspector);
        fileAccess = new VerifiedReviewM0FileAccess(byteSource, new ZipArchiveEntryOpener(limits));
        service = newService(repository, caseStore);
        downloads = new MaterialDownloadService(repository, fileAccess);

        ReviewM0StoreTestData.createCase(fixture, CASE_ID, OWNER);
        ReviewM0StoreTestData.publishSnapshot(fixture, CASE_ID, OWNER, 0);
        fixture.jdbc().update("DELETE FROM review_m0_upload_confirmation WHERE case_id=?", CASE_ID);
        fixture.jdbc().update("""
                        INSERT INTO review_m0_requirement(requirement_id, case_id, origin, created_at)
                        VALUES (?, ?, 'USER_ADDED', ?)
                        """, REQUIREMENT_ID, CASE_ID, Timestamp.from(ReviewM0StoreTestData.T0));
    }

    static Stream<Arguments> fiveTopLevelLeafTypes() {
        return Stream.of(
                Arguments.of("top-pdf", "original.pdf", "application/pdf",
                        SyntheticFileFixtures.pdf("top-pdf"), "logical.pdf", FormalFileType.PDF),
                Arguments.of("top-docx", "original.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        SyntheticFileFixtures.docx("top-docx"), "logical.docx", FormalFileType.DOCX),
                Arguments.of("top-xlsx", "original.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        SyntheticFileFixtures.xlsx("top-xlsx"), "logical.xlsx", FormalFileType.XLSX),
                Arguments.of("top-jpg", "original.jpg", "image/jpeg",
                        SyntheticFileFixtures.jpg((byte) 1), "logical.jpg", FormalFileType.JPG),
                Arguments.of("top-png", "original.png", "image/png",
                        SyntheticFileFixtures.png((byte) 2), "logical.png", FormalFileType.PNG));
    }

    @ParameterizedTest
    @MethodSource("fiveTopLevelLeafTypes")
    void fiveTopLevelLeafTypesAttachWithoutMutatingOriginal(
            String fileId,
            String originalName,
            String contentType,
            byte[] bytes,
            String logicalName,
            FormalFileType type
    ) {
        put(fileId, originalName, contentType, bytes);
        InMemoryOwnedFileByteSource.FileFacts before = byteSource.facts(fileId);

        MaterialAttachmentResult result = service.attachOwned(OWNER, CASE_ID, fileId, logicalName);

        assertEquals(type, result.source().formalFileType());
        assertEquals(1, result.materials().size());
        assertEquals(type, result.materials().get(0).formalFileType());
        assertEquals(MaterialStatus.AVAILABLE, result.materials().get(0).status());
        assertEquals(SyntheticFileFixtures.sha256(bytes), result.materials().get(0).contentDigest());
        assertEquals(1, result.materialSetRevision());
        assertEquals(before, byteSource.facts(fileId));
    }

    @Test
    void zipContainerCreatesFiveLeavesButNeverBecomesMaterial() {
        byte[] zip = fiveLeafZip();
        put("bundle", "bundle.zip", "application/zip", zip);

        MaterialAttachmentResult first = service.attachOwned(OWNER, CASE_ID, "bundle", null);

        assertEquals(FormalFileType.ZIP, first.source().formalFileType());
        assertEquals(5, first.materials().size());
        assertTrue(first.materials().stream().noneMatch(item -> item.formalFileType() == FormalFileType.ZIP));
        assertEquals(List.of("form.pdf", "letter.docx", "table.xlsx", "photo.jpg", "scan.png"),
                first.materials().stream().map(SubmissionMaterial::normalizedEntryPath).toList());
        assertEquals(List.of(1, 2, 3, 4, 5),
                first.materials().stream().map(SubmissionMaterial::entryIndex).toList());
        assertEquals(5, first.materials().stream().map(SubmissionMaterial::contentDigest).distinct().count());
        assertEquals(byteSource.facts("bundle").contentDigest(), first.source().fileDigest());
    }

    static Stream<Arguments> dangerousArchives() {
        byte[] safePdf = SyntheticFileFixtures.pdf("safe-first");
        byte[] ordinary = SyntheticFileFixtures.zip(List.of(entry("safe.pdf", safePdf)));
        return Stream.of(
                Arguments.of("nested", SyntheticFileFixtures.zip(List.of(
                        entry("safe.pdf", safePdf), entry("nested.zip", new byte[]{'P', 'K', 5, 6}))),
                        CannotConfirmReason.NESTED_ARCHIVE_NOT_ALLOWED),
                Arguments.of("encrypted", SyntheticFileFixtures.encryptedFirstEntry(ordinary),
                        CannotConfirmReason.ENCRYPTED),
                Arguments.of("corrupted", SyntheticFileFixtures.corruptFirstEntryData(ordinary),
                        CannotConfirmReason.CORRUPTED),
                Arguments.of("traversal", SyntheticFileFixtures.zip(List.of(
                        entry("../escape.pdf", safePdf))), CannotConfirmReason.CORRUPTED),
                Arguments.of("absolute", SyntheticFileFixtures.zip(List.of(
                        entry("/escape.pdf", safePdf))), CannotConfirmReason.CORRUPTED),
                Arguments.of("nul", SyntheticFileFixtures.zip(List.of(
                        entry("bad\u0000.pdf", safePdf))), CannotConfirmReason.CORRUPTED),
                Arguments.of("duplicate-nfc", SyntheticFileFixtures.zip(List.of(
                        entry("e\u0301.pdf", safePdf), entry("é.pdf", safePdf))), CannotConfirmReason.CORRUPTED));
    }

    @ParameterizedTest
    @MethodSource("dangerousArchives")
    void dangerousArchivePublishesNoPartialMaterial(
            String fileId,
            byte[] archive,
            CannotConfirmReason reason
    ) {
        put(fileId, fileId + ".zip", "application/zip", archive);

        MaterialAttachmentResult result = service.attachOwned(OWNER, CASE_ID, fileId, null);

        assertTrue(result.materials().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertEquals(reason, result.diagnostics().get(0).cannotConfirmReason());
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_material WHERE source_id=?", Integer.class,
                result.source().sourceId()));
        assertEquals(1, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_input_diagnostic WHERE source_id=?", Integer.class,
                result.source().sourceId()));
    }

    static Stream<Arguments> ordinaryCannotConfirmInputs() {
        return Stream.of(
                Arguments.of("encrypted.pdf", "application/pdf", SyntheticFileFixtures.encryptedPdf(),
                        CannotConfirmReason.ENCRYPTED),
                Arguments.of("corrupt.pdf", "application/pdf", "%PDF-1.4\ntruncated".getBytes(),
                        CannotConfirmReason.CORRUPTED),
                Arguments.of("mismatch.pdf", "application/pdf", SyntheticFileFixtures.png((byte) 1),
                        CannotConfirmReason.TYPE_MISMATCH));
    }

    @ParameterizedTest
    @MethodSource("ordinaryCannotConfirmInputs")
    void whiteListedDeepFailuresRemainCannotConfirmNotMissing(
            String originalName,
            String contentType,
            byte[] bytes,
            CannotConfirmReason reason
    ) {
        put("deep", originalName, contentType, bytes);

        MaterialAttachmentResult attached = service.attachOwned(OWNER, CASE_ID, "deep", "logical.pdf");
        UploadConfirmation confirmation = service.confirmUploadOwned(
                OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);
        MaterialCollectionSnapshot ready = service.requireReviewReadyOwned(OWNER, CASE_ID);

        assertNotNull(confirmation);
        assertEquals(MaterialStatus.CANNOT_CONFIRM, attached.materials().get(0).status());
        assertEquals(reason, attached.materials().get(0).cannotConfirmReason());
        assertTrue(ready.hasCannotConfirm());
    }

    @Test
    void ownedButUnavailableWhiteListedSourceIsCannotConfirm() {
        byte[] bytes = SyntheticFileFixtures.pdf("unavailable");
        put("unavailable", "form.pdf", "application/pdf", bytes);
        byteSource.setAvailable("unavailable", false);

        MaterialAttachmentResult attached = service.attachOwned(
                OWNER, CASE_ID, "unavailable", "logical.pdf");

        assertEquals(CannotConfirmReason.SOURCE_FILE_UNAVAILABLE,
                attached.materials().get(0).cannotConfirmReason());
        assertEquals(0, byteSource.byteReads());
    }

    @Test
    void declaredTypeMismatchIsCannotConfirm() {
        put("declared", "form.pdf", "image/png", SyntheticFileFixtures.pdf("declared"));

        MaterialAttachmentResult attached = service.attachOwned(
                OWNER, CASE_ID, "declared", "logical.pdf");

        assertEquals(CannotConfirmReason.TYPE_MISMATCH,
                attached.materials().get(0).cannotConfirmReason());
    }

    @Test
    void topLevelNonWhitelistReturnsFrozenUnsupportedTypeWithoutAttach() {
        put("legacy", "legacy.doc", "application/msword", new byte[]{1, 2, 3});

        ReviewM0Exception error = assertThrows(ReviewM0Exception.class,
                () -> service.attachOwned(OWNER, CASE_ID, "legacy", "legacy.doc"));

        assertEquals(ReviewM0ErrorCode.REVIEW_M0_UNSUPPORTED_FILE_TYPE, error.errorCode());
        assertEquals(0, repository.listSourcesOwned(OWNER, CASE_ID).size());
        assertEquals(0, caseStore.findOwned(OWNER, CASE_ID).orElseThrow().materialSetRevision());
    }

    @Test
    void zipRequiresNullLogicalContainerNameAndOrdinaryRequiresLeafName() {
        put("zip", "bundle.zip", "application/zip", fiveLeafZip());
        put("pdf", "form.pdf", "application/pdf", SyntheticFileFixtures.pdf("pdf"));

        assertInvalid(() -> service.attachOwned(OWNER, CASE_ID, "zip", "bundle.zip"));
        assertInvalid(() -> service.attachOwned(OWNER, CASE_ID, "pdf", null));
    }

    @Test
    void reviewGateRejectsBeforeCurrentUploadConfirmationWithoutReadingFiles() {
        put("pdf", "form.pdf", "application/pdf", SyntheticFileFixtures.pdf("gate"));
        service.attachOwned(OWNER, CASE_ID, "pdf", "logical.pdf");
        int readsAfterAttach = byteSource.byteReads();

        ReviewM0Exception error = assertThrows(ReviewM0Exception.class,
                () -> service.requireReviewReadyOwned(OWNER, CASE_ID));

        assertEquals(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED, error.errorCode());
        assertEquals(readsAfterAttach, byteSource.byteReads());
    }

    @Test
    void materialSetChangeInvalidatesConfirmationButNameAndAssignmentDoNot() throws Exception {
        put("first", "first.pdf", "application/pdf", SyntheticFileFixtures.pdf("first"));
        put("second", "second.pdf", "application/pdf", SyntheticFileFixtures.pdf("second"));
        SubmissionMaterial first = service.attachOwned(
                OWNER, CASE_ID, "first", "first.pdf").materials().get(0);
        UploadConfirmation confirmation = service.confirmUploadOwned(
                OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);

        service.renameOwned(OWNER, CASE_ID, first.materialId(), first.version(), "renamed.pdf");
        assertEquals(confirmation.uploadConfirmationId(),
                service.getSnapshotOwned(OWNER, CASE_ID).uploadConfirmation().uploadConfirmationId());
        service.replaceAssignmentsOwned(OWNER, CASE_ID, 0,
                List.of(new AssignmentChange(first.materialId(), REQUIREMENT_ID, AssignmentStatus.USER_CONFIRMED)));
        assertEquals(confirmation.uploadConfirmationId(),
                service.getSnapshotOwned(OWNER, CASE_ID).uploadConfirmation().uploadConfirmationId());

        service.attachOwned(OWNER, CASE_ID, "second", "second.pdf");
        assertNull(service.getSnapshotOwned(OWNER, CASE_ID).uploadConfirmation());
        ReviewM0Exception error = assertThrows(ReviewM0Exception.class,
                () -> service.requireReviewReadyOwned(OWNER, CASE_ID));
        assertEquals(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED, error.errorCode());
    }

    @Test
    void sourceRemovalInvalidatesUploadAndSoftRemovesMaterials() {
        put("pdf", "form.pdf", "application/pdf", SyntheticFileFixtures.pdf("remove"));
        MaterialAttachmentResult attached = service.attachOwned(OWNER, CASE_ID, "pdf", "form.pdf");
        service.confirmUploadOwned(OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);

        service.removeSourceOwned(OWNER, CASE_ID, attached.source().sourceId(), 0);
        MaterialCollectionSnapshot snapshot = service.getSnapshotOwned(OWNER, CASE_ID);

        assertEquals(2, snapshot.materialSetRevision());
        assertNull(snapshot.uploadConfirmation());
        assertEquals(MaterialStatus.REMOVED, snapshot.materials().get(0).status());
    }

    @Test
    void sourceDigestChangeAdvancesMaterialRevisionOutdatesReportAndRequiresFreshConfirmation() {
        byte[] original = SyntheticFileFixtures.pdf("source-before");
        put("pdf", "form.pdf", "application/pdf", original);
        service.attachOwned(OWNER, CASE_ID, "pdf", "form.pdf");
        UploadConfirmation confirmation = service.confirmUploadOwned(
                OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);
        ReviewReport report = publishCurrentReport(confirmation);
        byteSource.replaceBytes("pdf", SyntheticFileFixtures.pdf("source-after"), "version-2");

        ReviewM0Exception first = assertThrows(ReviewM0Exception.class,
                () -> service.requireReviewReadyOwned(OWNER, CASE_ID));

        assertEquals(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED, first.errorCode());
        MaterialCollectionSnapshot changed = service.getSnapshotOwned(OWNER, CASE_ID);
        assertEquals(2, changed.materialSetRevision());
        assertNull(changed.uploadConfirmation());
        assertEquals(ReviewReportFreshness.OUTDATED, findReport(report.reportId()).freshness());
        assertTrue(changed.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.cannotConfirmReason() == CannotConfirmReason.SOURCE_FILE_UNAVAILABLE));

        service.confirmUploadOwned(OWNER, CASE_ID, "clsnap-" + CASE_ID, 2, true);
        MaterialCollectionSnapshot ready = service.requireReviewReadyOwned(OWNER, CASE_ID);
        assertEquals(2, ready.materialSetRevision());
        assertTrue(ready.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.cannotConfirmReason() == CannotConfirmReason.SOURCE_FILE_UNAVAILABLE));
    }

    @Test
    void renameOnlyChangesReviewLogicalNameAndMakesCurrentReportOutdated() throws Exception {
        put("pdf", "upload_9281.pdf", "application/pdf", SyntheticFileFixtures.pdf("rename"));
        InMemoryOwnedFileByteSource.FileFacts before = byteSource.facts("pdf");
        SubmissionMaterial material = service.attachOwned(
                OWNER, CASE_ID, "pdf", "申请表.pdf").materials().get(0);
        UploadConfirmation confirmation = service.confirmUploadOwned(
                OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);
        ReviewReport report = publishCurrentReport(confirmation);

        SubmissionMaterial renamed = service.renameOwned(
                OWNER, CASE_ID, material.materialId(), material.version(), "国家奖学金申请表.PDF");

        assertEquals("国家奖学金申请表.PDF", renamed.submissionFileName());
        assertEquals(before, byteSource.facts("pdf"));
        assertEquals(ReviewReportFreshness.OUTDATED, findReport(report.reportId()).freshness());
        assertNotNull(service.getSnapshotOwned(OWNER, CASE_ID).uploadConfirmation());
    }

    @Test
    void assignmentChangeMakesCurrentReportOutdatedInSameTransaction() throws Exception {
        put("pdf", "form.pdf", "application/pdf", SyntheticFileFixtures.pdf("assign"));
        SubmissionMaterial material = service.attachOwned(
                OWNER, CASE_ID, "pdf", "form.pdf").materials().get(0);
        UploadConfirmation confirmation = service.confirmUploadOwned(
                OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);
        ReviewReport report = publishCurrentReport(confirmation);

        List<com.javaee.aiservice.review.m0.model.MaterialAssignment> assignments =
                service.replaceAssignmentsOwned(OWNER, CASE_ID, 0,
                        List.of(new AssignmentChange(
                                material.materialId(), REQUIREMENT_ID, AssignmentStatus.USER_CONFIRMED)));

        assertEquals(1, assignments.size());
        assertEquals(AssignmentStatus.USER_CONFIRMED, assignments.get(0).status());
        assertEquals(ReviewReportFreshness.OUTDATED, findReport(report.reportId()).freshness());
        assertNotNull(service.getSnapshotOwned(OWNER, CASE_ID).uploadConfirmation());
    }

    @Test
    void zipMultiMaterialWriteFailureRollsBackSourceLeavesRevisionAndReportOutdate() throws Exception {
        UploadConfirmation confirmation = service.confirmUploadOwned(
                OWNER, CASE_ID, "clsnap-" + CASE_ID, 0, true);
        ReviewReport report = publishCurrentReport(confirmation);
        put("zip", "bundle.zip", "application/zip", SyntheticFileFixtures.zip(List.of(
                entry("one.pdf", SyntheticFileFixtures.pdf("one")),
                entry("two.png", SyntheticFileFixtures.png((byte) 2)))));
        FailingSecondMaterialRepository failing = new FailingSecondMaterialRepository(fixture);
        MaterialLifecycleService failingService = newService(failing, caseStore);

        assertThrows(IllegalStateException.class,
                () -> failingService.attachOwned(OWNER, CASE_ID, "zip", null));

        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_submission_source", Integer.class));
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_material", Integer.class));
        assertEquals(0, caseStore.findOwned(OWNER, CASE_ID).orElseThrow().materialSetRevision());
        assertEquals(ReviewReportFreshness.CURRENT, findReport(report.reportId()).freshness());
    }

    @Test
    void failedRenameRollsBackLogicalNameRevisionAndReportOutdate() throws Exception {
        put("pdf", "form.pdf", "application/pdf", SyntheticFileFixtures.pdf("rollback-name"));
        SubmissionMaterial material = service.attachOwned(
                OWNER, CASE_ID, "pdf", "form.pdf").materials().get(0);
        UploadConfirmation confirmation = service.confirmUploadOwned(
                OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);
        ReviewReport report = publishCurrentReport(confirmation);
        MaterialLifecycleService failingService = newService(repository, new ThrowAfterAdvanceCaseStore(caseStore));

        assertThrows(IllegalStateException.class, () -> failingService.renameOwned(
                OWNER, CASE_ID, material.materialId(), material.version(), "renamed.pdf"));

        SubmissionMaterial unchanged = repository.findMaterialOwned(OWNER, CASE_ID, material.materialId()).orElseThrow();
        assertEquals("form.pdf", unchanged.submissionFileName());
        assertEquals(0, caseStore.findOwned(OWNER, CASE_ID).orElseThrow().nameRevision());
        assertEquals(ReviewReportFreshness.CURRENT, findReport(report.reportId()).freshness());
    }

    @Test
    void failedAssignmentRollsBackRowsRevisionAndReportOutdate() throws Exception {
        put("pdf", "form.pdf", "application/pdf", SyntheticFileFixtures.pdf("rollback-assignment"));
        SubmissionMaterial material = service.attachOwned(
                OWNER, CASE_ID, "pdf", "form.pdf").materials().get(0);
        UploadConfirmation confirmation = service.confirmUploadOwned(
                OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);
        ReviewReport report = publishCurrentReport(confirmation);
        MaterialLifecycleService failingService = newService(repository, new ThrowAfterAdvanceCaseStore(caseStore));

        assertThrows(IllegalStateException.class, () -> failingService.replaceAssignmentsOwned(
                OWNER, CASE_ID, 0,
                List.of(new AssignmentChange(
                        material.materialId(), REQUIREMENT_ID, AssignmentStatus.USER_CONFIRMED))));

        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM review_m0_material_assignment", Integer.class));
        assertEquals(0, caseStore.findOwned(OWNER, CASE_ID).orElseThrow().assignmentRevision());
        assertEquals(ReviewReportFreshness.CURRENT, findReport(report.reportId()).freshness());
    }

    @Test
    void allOwnerScopedOperationsAndDownloadHideCrossOwnerResources() throws Exception {
        put("pdf", "form.pdf", "application/pdf", SyntheticFileFixtures.pdf("owner"));
        SubmissionMaterial material = service.attachOwned(
                OWNER, CASE_ID, "pdf", "form.pdf").materials().get(0);
        ReviewM0StoreTestData.createCase(fixture, "other-case", "owner-2");

        assertNotFound(() -> service.getSnapshotOwned("owner-2", CASE_ID));
        assertNotFound(() -> service.renameOwned("owner-2", CASE_ID, material.materialId(), 0, "renamed.pdf"));
        assertNotFound(() -> service.removeSourceOwned(
                "owner-2", CASE_ID,
                repository.listSourcesOwned(OWNER, CASE_ID).get(0).sourceId(), 0));
        assertNotFound(() -> service.confirmUploadOwned(
                "owner-2", CASE_ID, "clsnap-" + CASE_ID, 1, true));
        assertNotFound(() -> service.replaceAssignmentsOwned(
                "owner-2", CASE_ID, 0, List.of()));
        assertNotFound(() -> downloads.openOwned("owner-2", CASE_ID, material.materialId()));
        assertNotFound(() -> service.attachOwned("owner-2", CASE_ID, "pdf", "form.pdf"));

        ReviewM0Exception hiddenFile = assertThrows(ReviewM0Exception.class,
                () -> service.attachOwned("owner-2", "other-case", "pdf", "form.pdf"));
        assertEquals(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, hiddenFile.errorCode());
    }

    @Test
    void ordinaryAndZipDownloadsReturnOnlyTargetBytesWithCurrentUnicodeName() throws Exception {
        byte[] ordinary = SyntheticFileFixtures.pdf("ordinary-download");
        byte[] target = SyntheticFileFixtures.png((byte) 4);
        byte[] other = SyntheticFileFixtures.pdf("other-entry");
        put("ordinary", "opaque.pdf", "application/pdf", ordinary);
        put("zip", "bundle.zip", "application/zip", SyntheticFileFixtures.zip(List.of(
                entry("other.pdf", other), entry("target.png", target))));
        SubmissionMaterial ordinaryMaterial = service.attachOwned(
                OWNER, CASE_ID, "ordinary", "old.pdf").materials().get(0);
        ordinaryMaterial = service.renameOwned(
                OWNER, CASE_ID, ordinaryMaterial.materialId(), ordinaryMaterial.version(), "申请表 2026.pdf");
        SubmissionMaterial zipMaterial = service.attachOwned(
                        OWNER, CASE_ID, "zip", null).materials().stream()
                .filter(item -> item.normalizedEntryPath().equals("target.png")).findFirst().orElseThrow();

        DownloadPayload ordinaryPayload = downloads.openOwned(OWNER, CASE_ID, ordinaryMaterial.materialId());
        DownloadPayload zipPayload = downloads.openOwned(OWNER, CASE_ID, zipMaterial.materialId());
        try (InputStream input = ordinaryPayload.stream()) {
            assertArrayEquals(ordinary, input.readAllBytes());
        }
        try (InputStream input = zipPayload.stream()) {
            assertArrayEquals(target, input.readAllBytes());
        }
        assertEquals(ordinary.length, ordinaryPayload.contentLength());
        assertEquals(-1, zipPayload.contentLength());
        assertTrue(ordinaryPayload.contentDisposition().contains("filename*=UTF-8''"));
        assertTrue(ordinaryPayload.contentDisposition().contains("%E7%94%B3%E8%AF%B7%E8%A1%A8"));
        assertFalse(ordinaryPayload.contentDisposition().contains("\r"));
        assertFalse(ordinaryPayload.contentDisposition().contains("\n"));
    }

    @Test
    void duplicateLogicalNamesRemainDistinctByMaterialIdAndBytes() throws Exception {
        byte[] first = SyntheticFileFixtures.pdf("first-same-name");
        byte[] second = SyntheticFileFixtures.pdf("second-same-name");
        put("first", "one.pdf", "application/pdf", first);
        put("second", "two.pdf", "application/pdf", second);
        SubmissionMaterial firstMaterial = service.attachOwned(
                OWNER, CASE_ID, "first", "same.pdf").materials().get(0);
        SubmissionMaterial secondMaterial = service.attachOwned(
                OWNER, CASE_ID, "second", "same.pdf").materials().get(0);

        assertNotEquals(firstMaterial.materialId(), secondMaterial.materialId());
        try (InputStream input = downloads.openOwned(OWNER, CASE_ID, firstMaterial.materialId()).stream()) {
            assertArrayEquals(first, input.readAllBytes());
        }
        try (InputStream input = downloads.openOwned(OWNER, CASE_ID, secondMaterial.materialId()).stream()) {
            assertArrayEquals(second, input.readAllBytes());
        }
    }

    @Test
    void fullLifecycleNeverMutatesOriginalBytesNameMetadataVersionOrObjectKey() throws Exception {
        byte[] bytes = SyntheticFileFixtures.pdf("immutable-lifecycle");
        put("immutable", "upload_9281.pdf", "application/pdf", bytes);
        InMemoryOwnedFileByteSource.FileFacts before = byteSource.facts("immutable");
        SubmissionMaterial material = service.attachOwned(
                OWNER, CASE_ID, "immutable", "initial.pdf").materials().get(0);
        service.confirmUploadOwned(OWNER, CASE_ID, "clsnap-" + CASE_ID, 1, true);
        service.requireReviewReadyOwned(OWNER, CASE_ID);
        SubmissionMaterial renamed = service.renameOwned(
                OWNER, CASE_ID, material.materialId(), material.version(), "renamed.pdf");
        service.replaceAssignmentsOwned(OWNER, CASE_ID, 0,
                List.of(new AssignmentChange(
                        material.materialId(), REQUIREMENT_ID, AssignmentStatus.USER_CONFIRMED)));
        try (InputStream input = downloads.openOwned(OWNER, CASE_ID, renamed.materialId()).stream()) {
            assertArrayEquals(bytes, input.readAllBytes());
        }

        assertEquals(before, byteSource.facts("immutable"));
    }

    @Test
    void uploadConfirmationRequiresExplicitTrue() {
        ReviewM0Exception error = assertThrows(ReviewM0Exception.class,
                () -> service.confirmUploadOwned(
                        OWNER, CASE_ID, "clsnap-" + CASE_ID, 0, false));
        assertEquals(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, error.errorCode());
    }

    private MaterialLifecycleService newService(
            ReviewM0MaterialRepository targetRepository,
            ReviewCaseStore targetCaseStore
    ) {
        return new MaterialLifecycleService(
                targetRepository, targetCaseStore, fileAccess, archiveInspector,
                contentInspector, fixture.transactions());
    }

    private void put(String fileId, String name, String contentType, byte[] bytes) {
        byteSource.put(OWNER, fileId, name, contentType, bytes, "version-1", "private/" + fileId);
    }

    private byte[] fiveLeafZip() {
        return SyntheticFileFixtures.zip(List.of(
                entry("form.pdf", SyntheticFileFixtures.pdf("zip-pdf")),
                entry("letter.docx", SyntheticFileFixtures.docx("zip-docx")),
                entry("table.xlsx", SyntheticFileFixtures.xlsx("zip-xlsx")),
                entry("photo.jpg", SyntheticFileFixtures.jpg((byte) 3)),
                entry("scan.png", SyntheticFileFixtures.png((byte) 4))));
    }

    private ReviewReport publishCurrentReport(UploadConfirmation confirmation) {
        ReviewCase current = caseStore.findOwned(OWNER, CASE_ID).orElseThrow();
        String jobId = "job-" + java.util.UUID.randomUUID();
        String worker = "worker-1";
        String fingerprint = SyntheticFileFixtures.sha256(jobId.getBytes());
        ReviewM0StoreTestData.createAndLeaseJob(
                fixture, jobId, CASE_ID, OWNER, ReviewJobType.FORMAL_REVIEW,
                "idem-" + jobId, worker, fingerprint);
        ReviewReport report = new ReviewReport(
                "report-" + jobId, CASE_ID, jobId,
                "REVIEW_M0_CONTRACT_V1", "engine-v1",
                ReviewReportFreshness.CURRENT, ReviewAssessment.M0_READY, fingerprint,
                current.currentChecklistSnapshotId(), confirmation.uploadConfirmationId(),
                current.basisRevision(), current.materialSetRevision(), current.assignmentRevision(),
                current.nameRevision(), null, 0, 0, 0,
                ReviewM0StoreTestData.T0.plusSeconds(4), null, null);
        JdbcReviewM0JobStore jobStore = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        new JdbcReviewReportStore(fixture.jdbc(), jobStore, fixture.transactions())
                .publishOwned(OWNER, worker, ReviewM0StoreTestData.T0.plusSeconds(5),
                        new ReportBundle(report, List.of(), List.of()));
        return report;
    }

    private ReviewReport findReport(String reportId) {
        JdbcReviewM0JobStore jobStore = new JdbcReviewM0JobStore(fixture.jdbc(), fixture.transactions());
        return new JdbcReviewReportStore(fixture.jdbc(), jobStore, fixture.transactions())
                .findOwned(OWNER, CASE_ID, reportId).orElseThrow();
    }

    private static void assertInvalid(ThrowingRunnable action) {
        ReviewM0Exception error = assertThrows(ReviewM0Exception.class, action::run);
        assertEquals(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, error.errorCode());
    }

    private static void assertNotFound(ThrowingRunnable action) {
        ReviewM0Exception error = assertThrows(ReviewM0Exception.class, action::run);
        assertTrue(error.errorCode() == ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND
                || error.errorCode() == ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FailingSecondMaterialRepository extends JdbcReviewM0MaterialRepository {
        private final AtomicInteger insertions = new AtomicInteger();

        private FailingSecondMaterialRepository(ReviewM0JdbcTestFixture fixture) {
            super(fixture.jdbc());
        }

        @Override
        public SubmissionMaterial insertMaterialOwned(String ownerId, SubmissionMaterial material) {
            if (insertions.incrementAndGet() == 2) {
                throw new IllegalStateException("synthetic second material failure");
            }
            return super.insertMaterialOwned(ownerId, material);
        }
    }

    private static final class ThrowAfterAdvanceCaseStore implements ReviewCaseStore {
        private final ReviewCaseStore delegate;

        private ThrowAfterAdvanceCaseStore(ReviewCaseStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public ReviewCase create(ReviewCase reviewCase) {
            return delegate.create(reviewCase);
        }

        @Override
        public Optional<ReviewCase> findOwned(String ownerId, String caseId) {
            return delegate.findOwned(ownerId, caseId);
        }

        @Override
        public Optional<ChecklistSnapshot> findChecklistSnapshotOwned(
                String ownerId,
                String caseId,
                String checklistSnapshotId
        ) {
            return delegate.findChecklistSnapshotOwned(ownerId, caseId, checklistSnapshotId);
        }

        @Override
        public ReviewCase advanceRevisionOwned(
                String ownerId,
                String caseId,
                long expectedVersion,
                RevisionKind revisionKind,
                String outdatedReason
        ) {
            delegate.advanceRevisionOwned(ownerId, caseId, expectedVersion, revisionKind, outdatedReason);
            throw new IllegalStateException("synthetic failure after revision advance");
        }

        @Override
        public ChecklistSnapshot publishChecklistSnapshotOwned(
                String ownerId,
                String caseId,
                long expectedVersion,
                ChecklistSnapshot snapshot,
                String outdatedReason
        ) {
            return delegate.publishChecklistSnapshotOwned(
                    ownerId, caseId, expectedVersion, snapshot, outdatedReason);
        }
    }
}
