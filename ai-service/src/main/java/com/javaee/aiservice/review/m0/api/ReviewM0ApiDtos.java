package com.javaee.aiservice.review.m0.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.javaee.aiservice.review.m0.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Frozen public HTTP shapes for Review M0. */
public final class ReviewM0ApiDtos {
    private ReviewM0ApiDtos() {
    }

    public interface StrictRequest {
        @JsonAnySetter
        default void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown Review M0 request field");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateCaseRequest(String title, ProcessingMode processingMode) implements StrictRequest {}

    public record CaseResponse(
            String caseId, String title, ProcessingMode processingMode,
            long basisRevision, long checklistRevision, long materialSetRevision,
            long assignmentRevision, long nameRevision, long version,
            String currentChecklistSnapshotId, String currentReportId,
            Instant createdAt, Instant updatedAt
    ) {}

    public record CaseListResponse(List<CaseResponse> items, String nextCursor) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AttachBasisRequest(String fileId, OfficialBasisRole role) implements StrictRequest {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExpectedVersionRequest(Long expectedVersion) implements StrictRequest {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReclassifyBasisRequest(Long expectedVersion, OfficialBasisRole role) implements StrictRequest {}

    public record DiagnosticResponse(
            CannotConfirmReason cannotConfirmReason,
            String failureStage,
            Object sourceLocator
    ) {}

    public record OfficialBasisResponse(
            String basisId, String fileId, OfficialBasisRole role, OfficialBasisStatus status,
            String originalFileNameSnapshot, String fileDigest, long basisRevision,
            long version, CannotConfirmReason cannotConfirmReason,
            List<DiagnosticResponse> diagnostics, Instant createdAt
    ) {}

    public record OfficialBasisListResponse(List<OfficialBasisResponse> items, long basisRevision) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateRemotePreviewRequest(ReviewJobType operation, Long basisRevision) implements StrictRequest {}

    public record SourceLocatorResponse(
            String entryPath, Integer page, String sheet, String cellRange,
            String block, String entryDigest
    ) {}

    public record RemotePreviewSegmentResponse(
            String segmentId, String basisId, SourceLocatorResponse sourceLocator,
            boolean included, String redactedText
    ) {}

    public record RemotePreviewResponse(
            String previewId, ReviewJobType operation, RemotePreviewStatus status,
            long basisRevision, List<RemotePreviewSegmentResponse> segments,
            String payloadDigest, long version, Instant createdAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EditRemotePreviewRequest(Long expectedVersion, List<RemotePreviewSegmentEdit> segments) implements StrictRequest {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RemotePreviewSegmentEdit(String segmentId, Boolean included, String redactedText) implements StrictRequest {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConfirmRemotePreviewRequest(Long expectedVersion, String acknowledgedPayloadDigest) implements StrictRequest {}

    public record RemoteConsentResponse(
            String consentId, String previewId, String payloadDigest, long basisRevision,
            ReviewJobType operation, String consumedByJobId, Instant confirmedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ChecklistExtractionRequest(Long basisRevision, ProcessingMode processingMode, String consentId) implements StrictRequest {}

    public record FileNameRuleResponse(FileNameRuleKind kind, String pattern, Boolean caseSensitive) {}

    public record ChecklistSourceRefResponse(
            String basisId, SourceLocatorResponse sourceLocator, String quotedText
    ) {}

    public record ChecklistItemResponse(
            String requirementId, String displayName, boolean enabled,
            RequirementApplicability applicability, int minimumCount,
            FileNameRuleResponse fileNameRule, RequirementOrigin origin,
            RequirementAuthorship authorship, long itemVersion,
            List<ChecklistSourceRefResponse> sourceRefs
    ) {}

    public record ChecklistDraftResponse(
            String checklistRevisionId, ChecklistLifecycle lifecycle, long basisRevision,
            long revision, List<ChecklistItemResponse> items
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FileNameRuleRequest(FileNameRuleKind kind, String pattern, Boolean caseSensitive) implements StrictRequest {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AddRequirementRequest(
            String displayName, Boolean enabled, RequirementApplicability applicability,
            Integer minimumCount, FileNameRuleRequest fileNameRule
    ) implements StrictRequest {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EditRequirementRequest(
            Long expectedVersion, String displayName, Boolean enabled,
            RequirementApplicability applicability, Integer minimumCount,
            FileNameRuleRequest fileNameRule
    ) implements StrictRequest {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PublishChecklistRequest(
            Long expectedChecklistRevision, List<String> acknowledgedRequirementIds
    ) implements StrictRequest {}

    public record ChecklistSnapshotSummaryResponse(
            String checklistSnapshotId, ChecklistLifecycle lifecycle, long basisRevision,
            long checklistRevision, String snapshotDigest, int itemCount,
            Instant confirmedAt
    ) {}

    public record ChecklistSnapshotResponse(
            String checklistSnapshotId, ChecklistLifecycle lifecycle, long basisRevision,
            long checklistRevision, String snapshotDigest, int itemCount,
            Instant confirmedAt, List<ChecklistItemResponse> items
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AttachSubmissionSourceRequest(String fileId, String submissionFileName) implements StrictRequest {}

    public record EntryLocatorResponse(String entryPath, Integer entryIndex) {}

    public record MaterialResponse(
            String materialId, MaterialKind kind, String submissionFileName,
            FormalFileType formalFileType, MaterialStatus status,
            EntryLocatorResponse entryLocator, String contentDigest, long version,
            CannotConfirmReason cannotConfirmReason
    ) {}

    public record SubmissionSourceResponse(
            String sourceId, String fileId, String originalFileNameSnapshot,
            String fileDigest, FormalFileType formalFileType,
            SubmissionSourceStatus status, long materialSetRevision,
            long version, List<MaterialResponse> materials,
            List<DiagnosticResponse> diagnostics
    ) {}

    public record SubmissionSourceListResponse(long materialSetRevision, List<SubmissionSourceResponse> items) {}

    public record MaterialListResponse(long materialSetRevision, long nameRevision, List<MaterialResponse> items) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RenameMaterialRequest(Long expectedVersion, String submissionFileName) implements StrictRequest {}

    public record RenamedMaterialResponse(
            String materialId, MaterialKind kind, String submissionFileName,
            FormalFileType formalFileType, MaterialStatus status,
            EntryLocatorResponse entryLocator, String contentDigest, long version,
            CannotConfirmReason cannotConfirmReason, long nameRevision
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AssignmentRequest(String materialId, String requirementId, AssignmentStatus status) implements StrictRequest {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReplaceAssignmentsRequest(Long expectedAssignmentRevision, List<AssignmentRequest> assignments) implements StrictRequest {}

    public record AssignmentResponse(String materialId, String requirementId, AssignmentStatus status) {}

    public record AssignmentListResponse(long assignmentRevision, List<AssignmentResponse> assignments) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UploadConfirmationRequest(
            String checklistSnapshotId, Long materialSetRevision, Boolean acknowledgedUploadComplete
    ) implements StrictRequest {}

    public record UploadConfirmationResponse(
            String uploadConfirmationId, String checklistSnapshotId,
            long materialSetRevision, Instant confirmedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SubmitReviewRequest(
            ReviewMode mode, String checklistSnapshotId, String uploadConfirmationId,
            String baseReportId, List<String> changedMaterialIds
    ) implements StrictRequest {}

    public record ProgressResponse(long completedUnits, long totalUnits) {}

    public record JobResultRefResponse(String resultType, String resultId) {}

    public record JobErrorResponse(ReviewM0ErrorCode errorCode, boolean retryable, String message) {}

    public record JobResponse(
            String jobId, ReviewJobType type, ReviewJobStatus status,
            ProgressResponse progress, int attempt, JobResultRefResponse resultRef,
            JobErrorResponse error, Instant createdAt, Instant startedAt, Instant finishedAt
    ) {}

    public record ReportSummaryResponse(
            int missingCount, int fileNameNoncompliantCount,
            int cannotConfirmCount, int ignoredCount
    ) {}

    public record FindingResponse(
            String findingId, FindingType type, CannotConfirmReason cannotConfirmReason,
            String requirementId, String materialId, String message, boolean ignored,
            List<Map<String, Object>> ruleEvidence,
            List<Map<String, Object>> materialEvidence
    ) {}

    public record ReportResponse(
            String reportId, String caseId, String contractVersion, String engineVersion,
            ReviewReportFreshness freshness, ReviewAssessment assessment,
            @JsonInclude(JsonInclude.Include.NON_NULL) ReviewAssessment effectiveAssessment,
            String inputFingerprint,
            String checklistSnapshotId, String uploadConfirmationId,
            long basisRevision, long materialSetRevision, long assignmentRevision,
            long nameRevision, String baseReportId, ReportSummaryResponse summary,
            List<FindingResponse> findings, Instant createdAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FindingDecisionRequest(FindingDecisionType decision, String reason) implements StrictRequest {}

    public record FindingDecisionResponse(
            String decisionId, String reportId, String findingId,
            FindingDecisionType decision, String reason, Instant decidedAt,
            ReportSummaryResponse effectiveSummary, ReviewAssessment effectiveAssessment
    ) {}

    public record ErrorResponse(
            ReviewM0ErrorCode errorCode, String message, String requestId,
            Map<String, Object> details
    ) {}
}
