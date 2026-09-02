package com.javaee.aiservice.review.m0.api;

import com.javaee.aiservice.review.m0.material.DownloadPayload;
import com.javaee.aiservice.security.RequestUserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.Base64;
import java.util.HexFormat;

import static com.javaee.aiservice.review.m0.api.ReviewM0ApiDtos.*;

@RestController
@RequestMapping("/api/review-m0")
public final class ReviewM0Controller {
    private final RequestUserContext users;
    private final ReviewM0ApiService service;

    public ReviewM0Controller(RequestUserContext users, ReviewM0ApiService service) {
        this.users = users;
        this.service = service;
    }

    @PostMapping("/cases")
    public ResponseEntity<CaseResponse> createCase(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateCaseRequest request
    ) {
        return response(service.createCase(owner(), key, request));
    }

    @GetMapping("/cases/{caseId}")
    public CaseResponse getCase(@PathVariable String caseId) {
        return service.getCase(owner(), caseId);
    }

    @PostMapping("/cases/{caseId}/official-bases")
    public ResponseEntity<OfficialBasisResponse> attachBasis(
            @PathVariable String caseId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AttachBasisRequest request
    ) {
        return response(service.attachBasis(owner(), caseId, key, request));
    }

    @DeleteMapping("/cases/{caseId}/official-bases/{basisId}")
    public ResponseEntity<OfficialBasisResponse> detachBasis(
            @PathVariable String caseId, @PathVariable String basisId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody ExpectedVersionRequest request
    ) {
        return response(service.detachBasis(owner(), caseId, basisId, key, request));
    }

    @GetMapping("/cases/{caseId}/official-bases")
    public OfficialBasisListResponse listBases(@PathVariable String caseId) {
        return service.listBases(owner(), caseId);
    }

    @PatchMapping("/cases/{caseId}/official-bases/{basisId}")
    public ResponseEntity<OfficialBasisResponse> reclassifyBasis(
            @PathVariable String caseId, @PathVariable String basisId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody ReclassifyBasisRequest request
    ) {
        return response(service.reclassifyBasis(owner(), caseId, basisId, key, request));
    }

    @PostMapping("/cases/{caseId}/remote-previews")
    public ResponseEntity<RemotePreviewResponse> createPreview(
            @PathVariable String caseId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateRemotePreviewRequest request
    ) {
        return response(service.createPreview(owner(), caseId, key, request));
    }

    @PatchMapping("/cases/{caseId}/remote-previews/{previewId}")
    public ResponseEntity<RemotePreviewResponse> editPreview(
            @PathVariable String caseId, @PathVariable String previewId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody EditRemotePreviewRequest request
    ) {
        return response(service.editPreview(owner(), caseId, previewId, key, request));
    }

    @PostMapping("/cases/{caseId}/remote-previews/{previewId}/confirm")
    public ResponseEntity<RemoteConsentResponse> confirmPreview(
            @PathVariable String caseId, @PathVariable String previewId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody ConfirmRemotePreviewRequest request
    ) {
        return response(service.confirmPreview(owner(), caseId, previewId, key, request));
    }

    @PostMapping("/cases/{caseId}/checklist-extractions")
    public ResponseEntity<JobResponse> submitExtraction(
            @PathVariable String caseId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody ReviewM0ApiDtos.ChecklistExtractionRequest request
    ) {
        ReviewM0ApiService.JobSubmissionResponse result = service.submitExtraction(owner(), caseId, key, request);
        return ResponseEntity.status(result.status()).body(result.value());
    }

    @GetMapping("/cases/{caseId}/checklist-draft")
    public ChecklistDraftResponse getDraft(@PathVariable String caseId) {
        return service.getDraft(owner(), caseId);
    }

    @PostMapping("/cases/{caseId}/checklist-draft/requirements")
    public ResponseEntity<ChecklistDraftResponse> addRequirement(
            @PathVariable String caseId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody AddRequirementRequest request
    ) {
        return response(service.addRequirement(owner(), caseId, key, request));
    }

    @PatchMapping("/cases/{caseId}/checklist-draft/requirements/{requirementId}")
    public ResponseEntity<ChecklistDraftResponse> editRequirement(
            @PathVariable String caseId, @PathVariable String requirementId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody EditRequirementRequest request
    ) {
        return response(service.editRequirement(owner(), caseId, requirementId, key, request));
    }

    @PostMapping("/cases/{caseId}/checklist-snapshots")
    public ResponseEntity<ChecklistSnapshotSummaryResponse> publishChecklist(
            @PathVariable String caseId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody PublishChecklistRequest request
    ) {
        return response(service.publishChecklist(owner(), caseId, key, request));
    }

    @GetMapping("/cases/{caseId}/checklist-snapshots/{checklistSnapshotId}")
    public ChecklistSnapshotResponse getChecklistSnapshot(
            @PathVariable String caseId, @PathVariable String checklistSnapshotId
    ) {
        return service.getChecklistSnapshot(owner(), caseId, checklistSnapshotId);
    }

    @PostMapping("/cases/{caseId}/submission-sources")
    public ResponseEntity<SubmissionSourceResponse> attachSource(
            @PathVariable String caseId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody AttachSubmissionSourceRequest request
    ) {
        return response(service.attachSource(owner(), caseId, key, request));
    }

    @DeleteMapping("/cases/{caseId}/submission-sources/{sourceId}")
    public ResponseEntity<SubmissionSourceResponse> removeSource(
            @PathVariable String caseId, @PathVariable String sourceId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody ExpectedVersionRequest request
    ) {
        return response(service.removeSource(owner(), caseId, sourceId, key, request));
    }

    @GetMapping("/cases/{caseId}/submission-sources")
    public SubmissionSourceListResponse listSources(@PathVariable String caseId) {
        return service.listSources(owner(), caseId);
    }

    @GetMapping("/cases/{caseId}/materials")
    public MaterialListResponse listMaterials(@PathVariable String caseId) {
        return service.listMaterials(owner(), caseId);
    }

    @PatchMapping("/cases/{caseId}/materials/{materialId}")
    public ResponseEntity<RenamedMaterialResponse> renameMaterial(
            @PathVariable String caseId, @PathVariable String materialId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody RenameMaterialRequest request
    ) {
        return response(service.renameMaterial(owner(), caseId, materialId, key, request));
    }

    @PutMapping("/cases/{caseId}/assignments")
    public ResponseEntity<AssignmentListResponse> replaceAssignments(
            @PathVariable String caseId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody ReplaceAssignmentsRequest request
    ) {
        return response(service.replaceAssignments(owner(), caseId, key, request));
    }

    @GetMapping("/cases/{caseId}/assignments")
    public AssignmentListResponse getAssignments(@PathVariable String caseId) {
        return service.getAssignments(owner(), caseId);
    }

    @PostMapping("/cases/{caseId}/upload-confirmations")
    public ResponseEntity<UploadConfirmationResponse> confirmUpload(
            @PathVariable String caseId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody UploadConfirmationRequest request
    ) {
        return response(service.confirmUpload(owner(), caseId, key, request));
    }

    @GetMapping("/cases/{caseId}/materials/{materialId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String caseId, @PathVariable String materialId
    ) {
        DownloadPayload payload = service.download(owner(), caseId, materialId);
        StreamingResponseBody body = output -> {
            try (InputStream input = payload.stream()) {
                input.transferTo(output);
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, payload.contentDisposition())
                .header(HttpHeaders.ETAG, '"' + payload.contentDigest() + '"')
                .header("Digest", "sha-256=" + Base64.getEncoder().encodeToString(
                        HexFormat.of().parseHex(payload.contentDigest())))
                .header("X-Content-SHA256", payload.contentDigest());
        if (payload.contentLength() >= 0) response.contentLength(payload.contentLength());
        return response.body(body);
    }

    @PostMapping("/cases/{caseId}/reviews")
    public ResponseEntity<JobResponse> submitReview(
            @PathVariable String caseId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody SubmitReviewRequest request
    ) {
        ReviewM0ApiService.JobSubmissionResponse result = service.submitReview(owner(), caseId, key, request);
        return ResponseEntity.status(result.status()).body(result.value());
    }

    @GetMapping("/cases/{caseId}/reports/current")
    public ReportResponse currentReport(@PathVariable String caseId) {
        return service.currentReport(owner(), caseId);
    }

    @GetMapping("/cases/{caseId}/reports/{reportId}")
    public ReportResponse report(@PathVariable String caseId, @PathVariable String reportId) {
        return service.report(owner(), caseId, reportId);
    }

    @PostMapping("/cases/{caseId}/reports/{reportId}/findings/{findingId}/decisions")
    public ResponseEntity<FindingDecisionResponse> ignoreFinding(
            @PathVariable String caseId, @PathVariable String reportId, @PathVariable String findingId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody FindingDecisionRequest request
    ) {
        return response(service.ignoreFinding(owner(), caseId, reportId, findingId, key, request));
    }

    @GetMapping("/cases/{caseId}/jobs/{jobId}")
    public JobResponse getJob(@PathVariable String caseId, @PathVariable String jobId) {
        return service.getJob(owner(), caseId, jobId);
    }

    @PostMapping("/cases/{caseId}/jobs/{jobId}/cancel")
    public ResponseEntity<JobResponse> cancelJob(
            @PathVariable String caseId, @PathVariable String jobId,
            @RequestHeader("Idempotency-Key") String key
    ) {
        return response(service.cancelJob(owner(), caseId, jobId, key));
    }

    private String owner() {
        return users.getRequiredUserId();
    }

    private static <T> ResponseEntity<T> response(ReviewM0ApiIdempotency.Result<T> value) {
        return ResponseEntity.status(value.status()).body(value.value());
    }
}
