package com.javaee.aiservice.review.m0.api;

import com.javaee.aiservice.review.m0.checklist.*;
import com.javaee.aiservice.review.m0.extraction.ChecklistExtractionRequest;
import com.javaee.aiservice.review.m0.file.FileArtifactSnapshot;
import com.javaee.aiservice.review.m0.file.ReviewM0FileAccess;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmission;
import com.javaee.aiservice.review.m0.job.ReviewM0JobSubmissionService;
import com.javaee.aiservice.review.m0.material.*;
import com.javaee.aiservice.review.m0.model.*;
import com.javaee.aiservice.review.m0.privacy.JdbcRemotePrivacyStore;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewSegment;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewService;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewView;
import com.javaee.aiservice.review.m0.report.EffectiveReportView;
import com.javaee.aiservice.review.m0.report.FindingDecisionService;
import com.javaee.aiservice.review.m0.report.ReviewM0Ids;
import com.javaee.aiservice.review.m0.review.FormalReviewRequest;
import com.javaee.aiservice.review.m0.review.FormalReviewSubmissionService;
import com.javaee.aiservice.review.m0.store.ReviewCaseStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static com.javaee.aiservice.review.m0.api.ReviewM0ApiDtos.*;

/** Owner-scoped application service behind the frozen Review M0 controller. */
public final class ReviewM0ApiService {
    private final ReviewCaseStore cases;
    private final OfficialBasisService basisService;
    private final JdbcChecklistStore checklistStore;
    private final ChecklistService checklistService;
    private final RemotePreviewService previewService;
    private final JdbcRemotePrivacyStore privacyStore;
    private final ReviewM0RemotePreviewAssembler previewAssembler;
    private final ReviewM0FileAccess fileAccess;
    private final MaterialLifecycleService materialService;
    private final JdbcReviewM0MaterialRepository materialRepository;
    private final MaterialDownloadService downloadService;
    private final FormalReviewSubmissionService reviewSubmissionService;
    private final ReviewM0JobSubmissionService jobSubmissionService;
    private final ReviewM0JobStore jobs;
    private final FindingDecisionService decisionService;
    private final ReviewM0ApiIdempotency idempotency;
    private final ReviewM0ApiMapper mapper;
    private final M0IdGenerator ids;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReviewM0ApiService(
            ReviewCaseStore cases,
            OfficialBasisService basisService,
            JdbcChecklistStore checklistStore,
            ChecklistService checklistService,
            RemotePreviewService previewService,
            JdbcRemotePrivacyStore privacyStore,
            ReviewM0RemotePreviewAssembler previewAssembler,
            ReviewM0FileAccess fileAccess,
            MaterialLifecycleService materialService,
            JdbcReviewM0MaterialRepository materialRepository,
            MaterialDownloadService downloadService,
            FormalReviewSubmissionService reviewSubmissionService,
            ReviewM0JobSubmissionService jobSubmissionService,
            ReviewM0JobStore jobs,
            FindingDecisionService decisionService,
            ReviewM0ApiIdempotency idempotency,
            ReviewM0ApiMapper mapper,
            M0IdGenerator ids,
            JdbcTemplate jdbc,
            Clock clock
    ) {
        this.cases = Objects.requireNonNull(cases);
        this.basisService = Objects.requireNonNull(basisService);
        this.checklistStore = Objects.requireNonNull(checklistStore);
        this.checklistService = Objects.requireNonNull(checklistService);
        this.previewService = Objects.requireNonNull(previewService);
        this.privacyStore = Objects.requireNonNull(privacyStore);
        this.previewAssembler = Objects.requireNonNull(previewAssembler);
        this.fileAccess = Objects.requireNonNull(fileAccess);
        this.materialService = Objects.requireNonNull(materialService);
        this.materialRepository = Objects.requireNonNull(materialRepository);
        this.downloadService = Objects.requireNonNull(downloadService);
        this.reviewSubmissionService = Objects.requireNonNull(reviewSubmissionService);
        this.jobSubmissionService = Objects.requireNonNull(jobSubmissionService);
        this.jobs = Objects.requireNonNull(jobs);
        this.decisionService = Objects.requireNonNull(decisionService);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.mapper = Objects.requireNonNull(mapper);
        this.ids = Objects.requireNonNull(ids);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReviewM0ApiIdempotency.Result<CaseResponse> createCase(
            String owner, String key, CreateCaseRequest request
    ) {
        require(request, "request");
        if (request.processingMode() == null) invalid("processingMode is required");
        Map<String, Object> body = map("title", request.title(), "processingMode", request.processingMode());
        IdempotencyScope scope = scope(owner, "POST", "/api/review-m0/cases", key);
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "CASE", () -> {
            Instant now = clock.instant();
            ReviewCase created = cases.create(new ReviewCase(
                    ids.next("case_"), owner, request.title(), request.processingMode(),
                    0, 0, 0, 0, 0, 0, null, null, now, now));
            return resource(created.caseId(), mapper.reviewCase(created));
        }, id -> mapper.reviewCase(requireCase(owner, id)));
    }

    public CaseResponse getCase(String owner, String caseId) {
        return mapper.reviewCase(requireCase(owner, caseId));
    }

    public ReviewM0ApiIdempotency.Result<OfficialBasisResponse> attachBasis(
            String owner, String caseId, String key, AttachBasisRequest request
    ) {
        require(request, "request");
        requireText(request.fileId(), "fileId");
        if (request.role() == null) invalid("role is required");
        IdempotencyScope scope = scope(owner, "POST", path(caseId, "/official-bases"), key);
        Map<String, Object> body = map("fileId", request.fileId(), "role", request.role());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "OFFICIAL_BASIS", () -> {
            ReviewCase reviewCase = requireCase(owner, caseId);
            FileArtifactSnapshot file = fileAccess.getOwnedFile(owner, request.fileId());
            if (!file.available()) {
                throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE,
                        "Owned file is unavailable");
            }
            FormalFileType.fromFileName(file.originalFileName()).orElseThrow(() ->
                    new ReviewM0Exception(
                            ReviewM0ErrorCode.REVIEW_M0_UNSUPPORTED_FILE_TYPE,
                            "Official basis file type is unsupported"));
            OfficialBasis created = basisService.attachOwned(owner, caseId, reviewCase.version(),
                    new OfficialBasisAttachment(
                            ids.next("basis_"), file.fileId(), request.role(), file.originalFileName(),
                            file.declaredContentType(), file.sizeBytes(), file.contentDigest(), file.storageVersion()));
            ReviewCase after = requireCase(owner, caseId);
            return resource(created.basisId(), mapper.basis(created, after.basisRevision(), basisDiagnostics(owner, caseId, created.basisId())));
        }, id -> basisResponse(owner, caseId, id));
    }

    public ReviewM0ApiIdempotency.Result<OfficialBasisResponse> detachBasis(
            String owner, String caseId, String basisId, String key, ExpectedVersionRequest request
    ) {
        long expected = expectedVersion(request == null ? null : request.expectedVersion());
        IdempotencyScope scope = scope(owner, "DELETE", path(caseId, "/official-bases/" + basisId), key);
        return idempotency.execute(scope, CanonicalJson.sha256(map("expectedVersion", expected)),
                200, "OFFICIAL_BASIS", () -> {
                    ReviewCase reviewCase = requireCase(owner, caseId);
                    OfficialBasis value = basisService.detachOwned(
                            owner, caseId, basisId, reviewCase.version(), expected);
                    return resource(value.basisId(), basisResponse(owner, caseId, value.basisId()));
                }, id -> basisResponse(owner, caseId, id));
    }

    public OfficialBasisListResponse listBases(String owner, String caseId) {
        ReviewCase reviewCase = requireCase(owner, caseId);
        return new OfficialBasisListResponse(
                basisService.listOwned(owner, caseId).stream()
                        .map(value -> mapper.basis(value, reviewCase.basisRevision(),
                                basisDiagnostics(owner, caseId, value.basisId())))
                        .toList(), reviewCase.basisRevision());
    }

    public ReviewM0ApiIdempotency.Result<OfficialBasisResponse> reclassifyBasis(
            String owner, String caseId, String basisId, String key, ReclassifyBasisRequest request
    ) {
        require(request, "request");
        long expected = expectedVersion(request.expectedVersion());
        if (request.role() == null) invalid("role is required");
        IdempotencyScope scope = scope(owner, "PATCH", path(caseId, "/official-bases/" + basisId), key);
        Map<String, Object> body = map("expectedVersion", expected, "role", request.role());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 200, "OFFICIAL_BASIS", () -> {
            ReviewCase reviewCase = requireCase(owner, caseId);
            OfficialBasis value = basisService.reclassifyOwned(
                    owner, caseId, basisId, reviewCase.version(), expected, request.role());
            return resource(value.basisId(), basisResponse(owner, caseId, value.basisId()));
        }, id -> basisResponse(owner, caseId, id));
    }

    public ReviewM0ApiIdempotency.Result<RemotePreviewResponse> createPreview(
            String owner, String caseId, String key, CreateRemotePreviewRequest request
    ) {
        require(request, "request");
        if (request.operation() == null || request.basisRevision() == null) invalid("Preview request is incomplete");
        IdempotencyScope scope = scope(owner, "POST", path(caseId, "/remote-previews"), key);
        Map<String, Object> body = map("operation", request.operation(), "basisRevision", request.basisRevision());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "REMOTE_PREVIEW", () -> {
            List<RemotePreviewSegment> redacted = previewAssembler.assembleOwned(
                    owner, caseId, request.basisRevision());
            RemotePreviewView value = previewService.createOwned(
                    owner, caseId, request.operation(), request.basisRevision(), redacted);
            return resource(value.preview().previewId(), mapper.preview(value));
        }, id -> mapper.preview(previewService.requireOwned(owner, caseId, id)));
    }

    public ReviewM0ApiIdempotency.Result<RemotePreviewResponse> editPreview(
            String owner, String caseId, String previewId, String key, EditRemotePreviewRequest request
    ) {
        require(request, "request");
        long expected = expectedVersion(request.expectedVersion());
        if (request.segments() == null) invalid("segments is required");
        IdempotencyScope scope = scope(owner, "PATCH", path(caseId, "/remote-previews/" + previewId), key);
        Map<String, Object> body = map("expectedVersion", expected, "segments", request.segments().stream()
                .map(item -> map("segmentId", item.segmentId(), "included", item.included(), "redactedText", item.redactedText())).toList());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 200, "REMOTE_PREVIEW", () -> {
            RemotePreviewView current = previewService.requireOwned(owner, caseId, previewId);
            Map<String, RemotePreviewSegment> byId = new HashMap<>();
            current.segments().forEach(segment -> byId.put(segment.segmentId(), segment));
            List<RemotePreviewSegment> edits = request.segments().stream().map(item -> {
                require(item, "segment");
                RemotePreviewSegment existing = byId.get(item.segmentId());
                if (existing == null || item.included() == null || item.redactedText() == null) {
                    throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                            "Remote preview segment edit is invalid");
                }
                return new RemotePreviewSegment(
                        existing.segmentId(), existing.basisId(), existing.sourceLocator(),
                        item.included(), item.redactedText());
            }).toList();
            RemotePreviewView updated = previewService.editOwned(owner, caseId, previewId, expected, edits);
            return resource(updated.preview().previewId(), mapper.preview(updated));
        }, id -> mapper.preview(previewService.requireOwned(owner, caseId, id)));
    }

    public ReviewM0ApiIdempotency.Result<RemoteConsentResponse> confirmPreview(
            String owner, String caseId, String previewId, String key, ConfirmRemotePreviewRequest request
    ) {
        require(request, "request");
        long expected = expectedVersion(request.expectedVersion());
        requireText(request.acknowledgedPayloadDigest(), "acknowledgedPayloadDigest");
        IdempotencyScope scope = scope(owner, "POST", path(caseId, "/remote-previews/" + previewId + "/confirm"), key);
        Map<String, Object> body = map("expectedVersion", expected,
                "acknowledgedPayloadDigest", request.acknowledgedPayloadDigest());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "REMOTE_CONSENT", () -> {
            RemoteConsent value = previewService.confirmOwned(owner, caseId, previewId, expected,
                    request.acknowledgedPayloadDigest());
            return resource(value.consentId(), mapper.consent(value));
        }, id -> mapper.consent(privacyStore.findConsentOwned(owner, caseId, id)
                .orElseThrow(ReviewM0ApiService::resourceNotFound).consent()));
    }

    public JobSubmissionResponse submitExtraction(
            String owner, String caseId, String key, ReviewM0ApiDtos.ChecklistExtractionRequest request
    ) {
        require(request, "request");
        if (request.basisRevision() == null || request.processingMode() == null) invalid("Extraction request is incomplete");
        ReviewCase reviewCase = requireCase(owner, caseId);
        if (reviewCase.basisRevision() != request.basisRevision()) {
            throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_BASIS_REVISION_STALE,
                    "Checklist extraction basis revision is stale");
        }
        if (reviewCase.processingMode() != request.processingMode()) invalid("processingMode does not match the case");
        ChecklistExtractionRequest value = new ChecklistExtractionRequest(
                request.basisRevision(), request.processingMode(), request.consentId());
        String canonicalPath = path(caseId, "/checklist-extractions");
        IdempotencyScope scope = scope(owner, "POST", canonicalPath, key);
        String clientDigest = CanonicalJson.sha256(map(
                "basisRevision", request.basisRevision(), "processingMode", request.processingMode(),
                "consentId", request.consentId()));
        Instant now = clock.instant();
        ReviewJob queued = new ReviewJob(
                ReviewM0Ids.next("job-"), caseId, owner, ReviewJobType.CHECKLIST_EXTRACTION,
                ReviewJobStatus.QUEUED, value.canonicalJson(), CanonicalJson.sha256(JSON_VALUE(value.canonicalJson())),
                null, key, 0, Math.max(1, basisService.listOwned(owner, caseId).size()), 0,
                null, null, null, null, null, 0, now, null, null, null);
        ReviewM0JobSubmission result = jobSubmissionService.submit(scope, clientDigest, queued);
        return new JobSubmissionResponse(mapper.job(result.job()), result.responseStatus(), result.replayed());
    }

    public ChecklistDraftResponse getDraft(String owner, String caseId) {
        return mapper.draft(checklistService.requireLatestDraftOwned(owner, caseId));
    }

    public ReviewM0ApiIdempotency.Result<ChecklistDraftResponse> addRequirement(
            String owner, String caseId, String key, AddRequirementRequest request
    ) {
        require(request, "request");
        FileNameRule rule = fileNameRule(request.fileNameRule());
        if (request.enabled() == null || request.applicability() == null || request.minimumCount() == null) {
            invalid("Checklist requirement is incomplete");
        }
        IdempotencyScope scope = scope(owner, "POST", path(caseId, "/checklist-draft/requirements"), key);
        Map<String, Object> body = requirementBody(request.displayName(), request.enabled(), request.applicability(), request.minimumCount(), request.fileNameRule());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "CHECKLIST_REVISION", () -> {
            ChecklistDraftView value = checklistService.addUserRequirementOwned(
                    owner, caseId, request.displayName(), request.enabled(), request.applicability(), request.minimumCount(), rule);
            return resource(value.checklistRevisionId(), mapper.draft(value));
        }, id -> mapper.draft(checklistStore.findRevisionByIdOwned(owner, caseId, id)
                .orElseThrow(ReviewM0ApiService::resourceNotFound)));
    }

    public ReviewM0ApiIdempotency.Result<ChecklistDraftResponse> editRequirement(
            String owner, String caseId, String requirementId, String key, EditRequirementRequest request
    ) {
        require(request, "request");
        long expected = expectedVersion(request.expectedVersion());
        FileNameRule rule = fileNameRule(request.fileNameRule());
        if (request.enabled() == null || request.applicability() == null || request.minimumCount() == null) {
            invalid("Checklist requirement is incomplete");
        }
        IdempotencyScope scope = scope(owner, "PATCH", path(caseId, "/checklist-draft/requirements/" + requirementId), key);
        Map<String, Object> body = requirementBody(request.displayName(), request.enabled(), request.applicability(), request.minimumCount(), request.fileNameRule());
        body.put("expectedVersion", expected);
        return idempotency.execute(scope, CanonicalJson.sha256(body), 200, "CHECKLIST_REVISION", () -> {
            ChecklistDraftView value = checklistService.editRequirementOwned(owner, caseId, requirementId,
                    new ChecklistItemEdit(expected, request.displayName(), request.enabled(),
                            request.applicability(), request.minimumCount(), rule));
            return resource(value.checklistRevisionId(), mapper.draft(value));
        }, id -> mapper.draft(checklistStore.findRevisionByIdOwned(owner, caseId, id)
                .orElseThrow(ReviewM0ApiService::resourceNotFound)));
    }

    public ReviewM0ApiIdempotency.Result<ChecklistSnapshotSummaryResponse> publishChecklist(
            String owner, String caseId, String key, PublishChecklistRequest request
    ) {
        require(request, "request");
        if (request.expectedChecklistRevision() == null || request.acknowledgedRequirementIds() == null) {
            invalid("Checklist publication request is incomplete");
        }
        List<String> acknowledged = request.acknowledgedRequirementIds().stream().sorted().toList();
        if (new HashSet<>(acknowledged).size() != acknowledged.size()) invalid("Acknowledged IDs must be unique");
        IdempotencyScope scope = scope(owner, "POST", path(caseId, "/checklist-snapshots"), key);
        Map<String, Object> body = map("expectedChecklistRevision", request.expectedChecklistRevision(),
                "acknowledgedRequirementIds", acknowledged);
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "CHECKLIST_SNAPSHOT", () -> {
            ReviewCase reviewCase = requireCase(owner, caseId);
            ChecklistSnapshot value = checklistService.publishOwned(owner, caseId, reviewCase.version(),
                    request.expectedChecklistRevision(), Set.copyOf(acknowledged));
            return resource(value.checklistSnapshotId(), mapper.snapshotSummary(value));
        }, id -> mapper.snapshotSummary(requireSnapshot(owner, caseId, id)));
    }

    public ChecklistSnapshotResponse getChecklistSnapshot(String owner, String caseId, String snapshotId) {
        return mapper.snapshot(requireSnapshot(owner, caseId, snapshotId));
    }

    public ReviewM0ApiIdempotency.Result<SubmissionSourceResponse> attachSource(
            String owner, String caseId, String key, AttachSubmissionSourceRequest request
    ) {
        require(request, "request");
        requireText(request.fileId(), "fileId");
        IdempotencyScope scope = scope(owner, "POST", path(caseId, "/submission-sources"), key);
        Map<String, Object> body = map("fileId", request.fileId(), "submissionFileName", request.submissionFileName());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "SUBMISSION_SOURCE", () -> {
            MaterialAttachmentResult value = materialService.attachOwned(
                    owner, caseId, request.fileId(), request.submissionFileName());
            return resource(value.source().sourceId(), mapper.attached(value));
        }, id -> sourceResponse(owner, caseId, id));
    }

    public ReviewM0ApiIdempotency.Result<SubmissionSourceResponse> removeSource(
            String owner, String caseId, String sourceId, String key, ExpectedVersionRequest request
    ) {
        long expected = expectedVersion(request == null ? null : request.expectedVersion());
        IdempotencyScope scope = scope(owner, "DELETE", path(caseId, "/submission-sources/" + sourceId), key);
        return idempotency.execute(scope, CanonicalJson.sha256(map("expectedVersion", expected)),
                200, "SUBMISSION_SOURCE", () -> {
                    SubmissionSource value = materialService.removeSourceOwned(owner, caseId, sourceId, expected);
                    return resource(value.sourceId(), sourceResponse(owner, caseId, value.sourceId()));
                }, id -> sourceResponse(owner, caseId, id));
    }

    public SubmissionSourceListResponse listSources(String owner, String caseId) {
        MaterialCollectionSnapshot snapshot = materialService.getSnapshotOwned(owner, caseId);
        return new SubmissionSourceListResponse(snapshot.materialSetRevision(), snapshot.sources().stream()
                .map(source -> sourceFromSnapshot(source, snapshot)).toList());
    }

    public MaterialListResponse listMaterials(String owner, String caseId) {
        MaterialCollectionSnapshot snapshot = materialService.getSnapshotOwned(owner, caseId);
        return new MaterialListResponse(snapshot.materialSetRevision(), snapshot.nameRevision(),
                snapshot.materials().stream().map(mapper::material).toList());
    }

    public ReviewM0ApiIdempotency.Result<RenamedMaterialResponse> renameMaterial(
            String owner, String caseId, String materialId, String key, RenameMaterialRequest request
    ) {
        require(request, "request");
        long expected = expectedVersion(request.expectedVersion());
        IdempotencyScope scope = scope(owner, "PATCH", path(caseId, "/materials/" + materialId), key);
        Map<String, Object> body = map("expectedVersion", expected, "submissionFileName", request.submissionFileName());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 200, "MATERIAL", () -> {
            SubmissionMaterial value = materialService.renameOwned(
                    owner, caseId, materialId, expected, request.submissionFileName());
            return resource(value.materialId(), mapper.renamed(value, requireCase(owner, caseId).nameRevision()));
        }, id -> mapper.renamed(materialRepository.findMaterialOwned(owner, caseId, id)
                .orElseThrow(ReviewM0ApiService::resourceNotFound), requireCase(owner, caseId).nameRevision()));
    }

    public ReviewM0ApiIdempotency.Result<AssignmentListResponse> replaceAssignments(
            String owner, String caseId, String key, ReplaceAssignmentsRequest request
    ) {
        require(request, "request");
        if (request.expectedAssignmentRevision() == null || request.assignments() == null) {
            invalid("Assignment request is incomplete");
        }
        List<AssignmentRequest> stable = request.assignments().stream()
                .sorted(Comparator.comparing(AssignmentRequest::materialId, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(AssignmentRequest::requirementId, Comparator.nullsFirst(String::compareTo)))
                .toList();
        Map<String, Object> body = map("expectedAssignmentRevision", request.expectedAssignmentRevision(),
                "assignments", stable.stream().map(item -> map("materialId", item.materialId(),
                        "requirementId", item.requirementId(), "status", item.status())).toList());
        IdempotencyScope scope = scope(owner, "PUT", path(caseId, "/assignments"), key);
        return idempotency.execute(scope, CanonicalJson.sha256(body), 200, "ASSIGNMENTS", () -> {
            List<AssignmentChange> changes = stable.stream().map(item ->
                    new AssignmentChange(item.materialId(), item.requirementId(), item.status())).toList();
            List<MaterialAssignment> values = materialService.replaceAssignmentsOwned(
                    owner, caseId, request.expectedAssignmentRevision(), changes);
            ReviewCase after = requireCase(owner, caseId);
            AssignmentListResponse response = new AssignmentListResponse(after.assignmentRevision(),
                    values.stream().map(mapper::assignment).toList());
            return resource(caseId, response);
        }, ignored -> getAssignments(owner, caseId));
    }

    public AssignmentListResponse getAssignments(String owner, String caseId) {
        MaterialCollectionSnapshot snapshot = materialService.getSnapshotOwned(owner, caseId);
        return new AssignmentListResponse(snapshot.assignmentRevision(),
                snapshot.assignments().stream().map(mapper::assignment).toList());
    }

    public ReviewM0ApiIdempotency.Result<UploadConfirmationResponse> confirmUpload(
            String owner, String caseId, String key, UploadConfirmationRequest request
    ) {
        require(request, "request");
        if (request.materialSetRevision() == null || request.acknowledgedUploadComplete() == null) {
            invalid("Upload confirmation request is incomplete");
        }
        IdempotencyScope scope = scope(owner, "POST", path(caseId, "/upload-confirmations"), key);
        Map<String, Object> body = map("checklistSnapshotId", request.checklistSnapshotId(),
                "materialSetRevision", request.materialSetRevision(),
                "acknowledgedUploadComplete", request.acknowledgedUploadComplete());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "UPLOAD_CONFIRMATION", () -> {
            UploadConfirmation value = materialService.confirmUploadOwned(
                    owner, caseId, request.checklistSnapshotId(), request.materialSetRevision(),
                    request.acknowledgedUploadComplete());
            return resource(value.uploadConfirmationId(), mapper.upload(value));
        }, id -> mapper.upload(requireUpload(owner, caseId, id)));
    }

    public DownloadPayload download(String owner, String caseId, String materialId) {
        requireCase(owner, caseId);
        return downloadService.openOwned(owner, caseId, materialId);
    }

    public JobSubmissionResponse submitReview(
            String owner, String caseId, String key, SubmitReviewRequest request
    ) {
        require(request, "request");
        FormalReviewRequest value = new FormalReviewRequest(
                request.mode(), request.checklistSnapshotId(), request.uploadConfirmationId(),
                request.baseReportId(), request.changedMaterialIds());
        ReviewM0JobSubmission result = reviewSubmissionService.submitOwned(
                scope(owner, "POST", path(caseId, "/reviews"), key), caseId, value);
        return new JobSubmissionResponse(mapper.job(result.job()), result.responseStatus(), result.replayed());
    }

    public ReportResponse currentReport(String owner, String caseId) {
        ReviewCase reviewCase = requireCase(owner, caseId);
        if (reviewCase.currentReportId() == null) {
            throw new ReviewM0ResourceNotFoundException("REPORT");
        }
        return report(owner, caseId, reviewCase.currentReportId());
    }

    public ReportResponse report(String owner, String caseId, String reportId) {
        EffectiveReportView view = decisionService.getEffectiveOwned(owner, caseId, reportId);
        return mapper.report(owner, caseId, view);
    }

    public ReviewM0ApiIdempotency.Result<FindingDecisionResponse> ignoreFinding(
            String owner, String caseId, String reportId, String findingId,
            String key, FindingDecisionRequest request
    ) {
        require(request, "request");
        if (request.decision() != FindingDecisionType.IGNORE) invalid("Only IGNORE is supported");
        IdempotencyScope scope = scope(owner, "POST", path(caseId,
                "/reports/" + reportId + "/findings/" + findingId + "/decisions"), key);
        Map<String, Object> body = map("decision", request.decision(), "reason", request.reason());
        return idempotency.execute(scope, CanonicalJson.sha256(body), 201, "FINDING_DECISION", () -> {
            FindingDecision value = decisionService.ignoreOwned(owner, caseId, reportId, findingId, request.reason());
            return resource(value.decisionId(), decisionResponse(owner, caseId, value));
        }, ignored -> decisionResponse(owner, caseId,
                decisionService.ignoreOwned(owner, caseId, reportId, findingId, request.reason())));
    }

    public JobResponse getJob(String owner, String caseId, String jobId) {
        requireCase(owner, caseId);
        return mapper.job(jobs.findOwned(owner, caseId, jobId).orElseThrow(ReviewM0ApiService::resourceNotFound));
    }

    public ReviewM0ApiIdempotency.Result<JobResponse> cancelJob(
            String owner, String caseId, String jobId, String key
    ) {
        requireCase(owner, caseId);
        IdempotencyScope scope = scope(owner, "POST", path(caseId, "/jobs/" + jobId + "/cancel"), key);
        return idempotency.executeDynamic(scope, CanonicalJson.sha256(Map.of()), "JOB_CANCEL", () -> {
            ReviewJob value = jobs.requestCancelOwned(owner, caseId, jobId, clock.instant());
            return new ReviewM0ApiIdempotency.DynamicResource<>(
                    value.jobId(), mapper.job(value), value.status().terminal() ? 200 : 202);
        }, id -> getJob(owner, caseId, id));
    }

    private OfficialBasisResponse basisResponse(String owner, String caseId, String basisId) {
        ReviewCase reviewCase = requireCase(owner, caseId);
        OfficialBasis value = checklistStore.findBasisOwned(owner, caseId, basisId)
                .orElseThrow(ReviewM0ApiService::resourceNotFound);
        return mapper.basis(value, reviewCase.basisRevision(), basisDiagnostics(owner, caseId, basisId));
    }

    private List<DiagnosticResponse> basisDiagnostics(String owner, String caseId, String basisId) {
        return jdbc.query("""
                        SELECT diagnostic.cannot_confirm_reason, diagnostic.failure_stage, diagnostic.locator_json
                        FROM review_m0_input_diagnostic diagnostic
                        JOIN review_m0_case review_case ON review_case.case_id=diagnostic.case_id
                        WHERE diagnostic.case_id=? AND diagnostic.basis_id=? AND review_case.user_id=?
                        ORDER BY diagnostic.created_at, diagnostic.diagnostic_id
                        """, (rs, row) -> new DiagnosticResponse(
                        CannotConfirmReason.valueOf(rs.getString(1)), rs.getString(2), null),
                caseId, basisId, owner);
    }

    private SubmissionSourceResponse sourceResponse(String owner, String caseId, String sourceId) {
        MaterialCollectionSnapshot snapshot = materialService.getSnapshotOwned(owner, caseId);
        SubmissionSource source = snapshot.sources().stream().filter(item -> item.sourceId().equals(sourceId))
                .findFirst().orElseThrow(ReviewM0ApiService::resourceNotFound);
        return sourceFromSnapshot(source, snapshot);
    }

    private SubmissionSourceResponse sourceFromSnapshot(SubmissionSource source, MaterialCollectionSnapshot snapshot) {
        List<SubmissionMaterial> materials = snapshot.materials().stream()
                .filter(item -> item.sourceId().equals(source.sourceId())).toList();
        Set<String> materialIds = materials.stream().map(SubmissionMaterial::materialId).collect(java.util.stream.Collectors.toSet());
        List<InputDiagnostic> diagnostics = snapshot.diagnostics().stream()
                .filter(item -> source.sourceId().equals(item.sourceId())
                        || item.materialId() != null && materialIds.contains(item.materialId())).toList();
        return mapper.source(source, snapshot.materialSetRevision(), materials, diagnostics);
    }

    private UploadConfirmation requireUpload(String owner, String caseId, String id) {
        requireCase(owner, caseId);
        return jdbc.query("""
                        SELECT upload_confirmation_id, case_id, checklist_snapshot_id,
                               material_set_revision, material_set_digest, confirmed_by, confirmed_at
                        FROM review_m0_upload_confirmation
                        WHERE upload_confirmation_id=? AND case_id=? AND confirmed_by=?
                        """, (rs, row) -> new UploadConfirmation(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()),
                id, caseId, owner).stream().findFirst().orElseThrow(ReviewM0ApiService::resourceNotFound);
    }

    private ChecklistSnapshot requireSnapshot(String owner, String caseId, String id) {
        requireCase(owner, caseId);
        return cases.findChecklistSnapshotOwned(owner, caseId, id)
                .orElseThrow(ReviewM0ApiService::resourceNotFound);
    }

    private FindingDecisionResponse decisionResponse(String owner, String caseId, FindingDecision value) {
        EffectiveReportView effective = decisionService.getEffectiveOwned(owner, caseId, value.reportId());
        ReviewReport report = effective.published().report();
        return new FindingDecisionResponse(
                value.decisionId(), value.reportId(), value.findingId(), value.decisionType(),
                value.reason(), value.decidedAt(), new ReportSummaryResponse(
                report.missingCount(), report.fileNameNoncompliantCount(), report.cannotConfirmCount(),
                effective.ignoredCount()), effective.effectiveAssessment());
    }

    private ReviewCase requireCase(String owner, String caseId) {
        return cases.findOwned(owner, caseId).orElseThrow(() ->
                new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND,
                        "Review case was not found"));
    }

    private static FileNameRule fileNameRule(FileNameRuleRequest request) {
        if (request == null) invalid("fileNameRule is required");
        return new FileNameRule(request.kind(), request.pattern(), request.caseSensitive());
    }

    private static Map<String, Object> requirementBody(
            String displayName, Boolean enabled, RequirementApplicability applicability,
            Integer minimumCount, FileNameRuleRequest rule
    ) {
        Map<String, Object> fileNameRule = rule == null ? null
                : map("kind", rule.kind(), "pattern", rule.pattern(), "caseSensitive", rule.caseSensitive());
        return map("displayName", displayName, "enabled", enabled, "applicability", applicability,
                "minimumCount", minimumCount, "fileNameRule", fileNameRule);
    }

    private static long expectedVersion(Long value) {
        if (value == null || value < 0) invalid("expectedVersion is required and must be non-negative");
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) invalid(name + " is required");
    }

    private static <T> T require(T value, String name) {
        if (value == null) invalid(name + " is required");
        return value;
    }

    private static void invalid(String message) {
        throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }

    private static ReviewM0Exception resourceNotFound() {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                "Review M0 resource was not found");
    }

    private static IdempotencyScope scope(String owner, String method, String path, String key) {
        try {
            return new IdempotencyScope(owner, method, path, key);
        } catch (IllegalArgumentException error) {
            throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                    "Idempotency-Key is required and must be valid");
        }
    }

    private static String path(String caseId, String suffix) {
        return "/api/review-m0/cases/" + caseId + suffix;
    }

    private static Map<String, Object> map(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static Object JSON_VALUE(String canonicalJson) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(canonicalJson, Object.class);
        } catch (Exception error) {
            throw new IllegalStateException("Canonical job request cannot be decoded", error);
        }
    }

    private static <T> ReviewM0ApiIdempotency.Resource<T> resource(String id, T value) {
        return new ReviewM0ApiIdempotency.Resource<>(id, value);
    }

    public record JobSubmissionResponse(JobResponse value, int status, boolean replayed) {}
}
