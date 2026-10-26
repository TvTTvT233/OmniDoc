package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.archive.ArchiveSafetyLimits;
import com.javaee.aiservice.review.m0.archive.ZipArchiveInspector;
import com.javaee.aiservice.review.m0.checklist.ChecklistService;
import com.javaee.aiservice.review.m0.checklist.JdbcChecklistStore;
import com.javaee.aiservice.review.m0.file.FormalContentInspector;
import com.javaee.aiservice.review.m0.file.OwnedFileAccessException;
import com.javaee.aiservice.review.m0.file.ReviewM0FileAccess;
import com.javaee.aiservice.review.m0.material.MaterialLifecycleService;
import com.javaee.aiservice.review.m0.material.ReviewM0MaterialRepository;
import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.MaterialAssignment;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;
import com.javaee.aiservice.review.m0.model.SubmissionSourceStatus;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentReviewInputProviderTest {
    private static final String OWNER = "owner-1";
    private static final String CASE_ID = "case-provider";
    private static final String SNAPSHOT_ID = "clsnap-provider";
    private static final String UPLOAD_ID = "upload-provider";
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    private final ReviewCaseStore cases = mock(ReviewCaseStore.class);
    private final ChecklistService checklists = mock(ChecklistService.class);
    private final JdbcChecklistStore checklistStore = mock(JdbcChecklistStore.class);
    private final ReviewM0MaterialRepository repository = mock(ReviewM0MaterialRepository.class);
    private final ReviewM0FileAccess fileAccess = mock(ReviewM0FileAccess.class);
    private final FormalContentInspector contentInspector = new FormalContentInspector(
            ArchiveSafetyLimits.secureDefaults());
    private final MaterialLifecycleService materials = new MaterialLifecycleService(
            repository, cases, fileAccess,
            new ZipArchiveInspector(ArchiveSafetyLimits.secureDefaults(), contentInspector),
            contentInspector, mock(PlatformTransactionManager.class));
    private final CurrentReviewInputProvider provider = new CurrentReviewInputProvider(
            cases, checklists, checklistStore, materials, DeterministicReviewEngine.ENGINE_VERSION);

    @BeforeEach
    void currentCaseAndChecklist() {
        when(cases.findOwned(OWNER, CASE_ID)).thenReturn(Optional.of(reviewCase()));
        when(checklists.requireCurrentFormalSnapshotOwned(OWNER, CASE_ID, SNAPSHOT_ID))
                .thenReturn(snapshot());
        when(checklistStore.listBasesOwned(OWNER, CASE_ID)).thenReturn(List.of());
        when(repository.listSourcesOwned(OWNER, CASE_ID)).thenReturn(List.of(source()));
        when(repository.listMaterialsOwned(OWNER, CASE_ID)).thenReturn(List.of(material()));
        when(repository.listDiagnosticsOwned(OWNER, CASE_ID)).thenReturn(List.of());
        when(repository.listAssignmentsOwned(OWNER, CASE_ID, 1))
                .thenReturn(List.of(assignment(), rejectedAssignment()));
    }

    @Test
    void capturesOnlyCurrentConfirmedFactsAndLogicalSubmissionName() {
        when(repository.findCurrentUploadConfirmationOwned(OWNER, CASE_ID, SNAPSHOT_ID, 2))
                .thenReturn(Optional.of(confirmation(2)));

        CapturedReviewInput input = provider.captureForSubmission(
                OWNER, CASE_ID, SNAPSHOT_ID, UPLOAD_ID);

        assertThat(input.manifest().materials()).singleElement().satisfies(material -> {
            assertThat(material.submissionFileName()).isEqualTo("logical-name.PDF");
            assertThat(material.formalFileType()).isEqualTo(FormalFileType.PDF);
        });
        assertThat(input.manifest().assignments()).singleElement().satisfies(assignment -> {
            assertThat(assignment.materialId()).isEqualTo("material-1");
            assertThat(assignment.status()).isEqualTo(AssignmentStatus.USER_CONFIRMED);
        });
        assertThat(input.manifest().uploadConfirmationId()).isEqualTo(UPLOAD_ID);
        verify(fileAccess, never()).getOwnedFile(OWNER, "file-1");
    }

    @Test
    void oldMaterialRevisionUploadConfirmationIsRejectedBeforeReview() {
        when(repository.findCurrentUploadConfirmationOwned(OWNER, CASE_ID, SNAPSHOT_ID, 2))
                .thenReturn(Optional.of(confirmation(1)));

        assertThatThrownBy(() -> provider.captureForSubmission(
                OWNER, CASE_ID, SNAPSHOT_ID, UPLOAD_ID))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED);
    }

    @Test
    void executionUsesOwnerAwareReviewReadyBoundaryAndPropagatesSystemFailure() {
        when(repository.findCurrentUploadConfirmationOwned(OWNER, CASE_ID, SNAPSHOT_ID, 2))
                .thenReturn(Optional.of(confirmation(2)));
        when(fileAccess.getOwnedFile(OWNER, "file-1")).thenThrow(new OwnedFileAccessException(
                OwnedFileAccessException.Kind.SERVICE_UNAVAILABLE,
                "synthetic owner-aware file failure"));

        assertThatThrownBy(() -> provider.captureForExecution(
                OWNER, CASE_ID, SNAPSHOT_ID, UPLOAD_ID))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE);
        verify(fileAccess).getOwnedFile(OWNER, "file-1");
    }

    @Test
    void crossOwnerCaseIsIndistinguishableFromMissing() {
        when(cases.findOwned("different-owner", CASE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.captureForSubmission(
                "different-owner", CASE_ID, SNAPSHOT_ID, UPLOAD_ID))
                .isInstanceOf(ReviewM0Exception.class)
                .extracting(error -> ((ReviewM0Exception) error).errorCode())
                .isEqualTo(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND);
        verify(checklists, never()).requireCurrentFormalSnapshotOwned(
                "different-owner", CASE_ID, SNAPSHOT_ID);
        verify(repository, never()).listSourcesOwned("different-owner", CASE_ID);
    }

    private static ReviewCase reviewCase() {
        return new ReviewCase(
                CASE_ID, OWNER, "Synthetic case", ProcessingMode.LOCAL_ONLY,
                1, 7, 2, 1, 1, 10, SNAPSHOT_ID, null, NOW, NOW);
    }

    private static ChecklistSnapshot snapshot() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("kind", "NONE");
        rule.put("pattern", null);
        rule.put("caseSensitive", null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("requirementId", "requirement-1");
        item.put("displayName", "Synthetic requirement");
        item.put("enabled", true);
        item.put("applicability", "APPLIES");
        item.put("minimumCount", 1);
        item.put("fileNameRule", rule);
        item.put("origin", "USER_ADDED");
        item.put("authorship", "USER_REWRITTEN");
        item.put("itemVersion", 1);
        item.put("sourceRefs", List.of());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("contractVersion", FormalChecklist.CONTRACT_VERSION);
        root.put("caseId", CASE_ID);
        root.put("basisRevision", 1);
        root.put("checklistRevision", 7);
        root.put("items", List.of(item));
        String json = CanonicalJson.write(root);
        return new ChecklistSnapshot(
                SNAPSHOT_ID, CASE_ID, "clrev-provider", 1, 7,
                json, CanonicalJson.sha256(root), ChecklistLifecycle.CONFIRMED,
                OWNER, NOW, null);
    }

    private static SubmissionSource source() {
        return new SubmissionSource(
                "source-1", CASE_ID, "file-1", "private-original-name.pdf",
                "application/pdf", FormalFileType.PDF, SubmissionSourceStatus.ATTACHED,
                100, M005Fixtures.DIGEST_B, "storage-v1", 2, null, 0, NOW, NOW);
    }

    private static SubmissionMaterial material() {
        return new SubmissionMaterial(
                "material-1", CASE_ID, "source-1", MaterialKind.TOP_LEVEL_FILE,
                null, null, null, M005Fixtures.DIGEST_A, FormalFileType.PDF,
                "logical-name.PDF", "logical-name.PDF", MaterialStatus.AVAILABLE,
                null, null, 2, 1, 0, NOW, NOW);
    }

    private static MaterialAssignment assignment() {
        return new MaterialAssignment(
                "assignment-1", CASE_ID, "material-1", "requirement-1",
                AssignmentStatus.USER_CONFIRMED, 1, OWNER, NOW);
    }

    private static MaterialAssignment rejectedAssignment() {
        return new MaterialAssignment(
                "assignment-2", CASE_ID, "material-1", "requirement-unconfirmed",
                AssignmentStatus.REJECTED, 1, OWNER, NOW);
    }

    private static UploadConfirmation confirmation(long confirmationRevision) {
        return new UploadConfirmation(
                UPLOAD_ID, CASE_ID, SNAPSHOT_ID, confirmationRevision,
                M005Fixtures.DIGEST_C, OWNER, NOW);
    }
}
