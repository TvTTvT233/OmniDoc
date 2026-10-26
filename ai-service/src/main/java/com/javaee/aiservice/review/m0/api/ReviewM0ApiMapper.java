package com.javaee.aiservice.review.m0.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.checklist.ChecklistDraftItem;
import com.javaee.aiservice.review.m0.checklist.ChecklistDraftView;
import com.javaee.aiservice.review.m0.checklist.ChecklistSourceRef;
import com.javaee.aiservice.review.m0.evidence.EvidenceDraft;
import com.javaee.aiservice.review.m0.material.MaterialAttachmentResult;
import com.javaee.aiservice.review.m0.model.*;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewSegment;
import com.javaee.aiservice.review.m0.privacy.RemotePreviewView;
import com.javaee.aiservice.review.m0.report.EffectiveReportView;
import com.javaee.aiservice.review.m0.review.FindingDraft;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static com.javaee.aiservice.review.m0.api.ReviewM0ApiDtos.*;

/** Deliberately whitelists public fields instead of serializing persistence models. */
public final class ReviewM0ApiMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public ReviewM0ApiMapper(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public CaseResponse reviewCase(ReviewCase value) {
        return new CaseResponse(
                value.caseId(), value.title(), value.processingMode(), value.basisRevision(),
                value.checklistRevision(), value.materialSetRevision(), value.assignmentRevision(),
                value.nameRevision(), value.version(), value.currentChecklistSnapshotId(),
                value.currentReportId(), value.createdAt(), value.updatedAt());
    }

    public OfficialBasisResponse basis(OfficialBasis value, long currentBasisRevision,
                                       List<DiagnosticResponse> diagnostics) {
        return new OfficialBasisResponse(
                value.basisId(), value.fileId(), value.role(), value.status(),
                value.originalFileNameSnapshot(), value.fileDigest(), currentBasisRevision,
                value.version(), value.cannotConfirmReason(), List.copyOf(diagnostics), value.createdAt());
    }

    public RemotePreviewResponse preview(RemotePreviewView value) {
        RemotePreview preview = value.preview();
        return new RemotePreviewResponse(
                preview.previewId(), preview.operation(), preview.status(), preview.basisRevision(),
                value.segments().stream().map(this::previewSegment).toList(),
                preview.payloadDigest(), preview.version(), preview.createdAt());
    }

    public RemoteConsentResponse consent(RemoteConsent value) {
        return new RemoteConsentResponse(
                value.consentId(), value.previewId(), value.payloadDigest(), value.basisRevision(),
                value.operation(), value.consumedByJobId(), value.confirmedAt());
    }

    public ChecklistDraftResponse draft(ChecklistDraftView value) {
        return new ChecklistDraftResponse(
                value.checklistRevisionId(), value.lifecycle(), value.basisRevision(),
                value.revision(), value.items().stream().map(this::checklistItem).toList());
    }

    public ChecklistSnapshotSummaryResponse snapshotSummary(ChecklistSnapshot value) {
        return new ChecklistSnapshotSummaryResponse(
                value.checklistSnapshotId(), value.lifecycle(), value.basisRevision(),
                value.checklistRevision(), value.snapshotDigest(), snapshotItemCount(value.snapshotJson()),
                value.confirmedAt());
    }

    public ChecklistSnapshotResponse snapshot(ChecklistSnapshot value) {
        return new ChecklistSnapshotResponse(
                value.checklistSnapshotId(), value.lifecycle(), value.basisRevision(),
                value.checklistRevision(), value.snapshotDigest(), snapshotItemCount(value.snapshotJson()),
                value.confirmedAt(), readSnapshotItems(value.snapshotJson()));
    }

    public SubmissionSourceResponse attached(MaterialAttachmentResult value) {
        return source(
                value.source(), value.materialSetRevision(), value.materials(), value.diagnostics());
    }

    public SubmissionSourceResponse source(
            SubmissionSource source,
            long materialSetRevision,
            List<SubmissionMaterial> materials,
            List<InputDiagnostic> diagnostics
    ) {
        return new SubmissionSourceResponse(
                source.sourceId(), source.fileId(), source.originalFileNameSnapshot(),
                source.fileDigest(), source.formalFileType(), source.status(), materialSetRevision,
                source.version(), materials.stream().map(this::material).toList(),
                diagnostics.stream().map(this::diagnostic).toList());
    }

    public MaterialResponse material(SubmissionMaterial value) {
        EntryLocatorResponse locator = value.kind() == MaterialKind.ZIP_ENTRY
                ? new EntryLocatorResponse(value.normalizedEntryPath(), value.entryIndex()) : null;
        return new MaterialResponse(
                value.materialId(), value.kind(), value.submissionFileName(), value.formalFileType(),
                value.status(), locator, value.contentDigest(), value.version(), value.cannotConfirmReason());
    }

    public RenamedMaterialResponse renamed(SubmissionMaterial value, long nameRevision) {
        MaterialResponse material = material(value);
        return new RenamedMaterialResponse(
                material.materialId(), material.kind(), material.submissionFileName(),
                material.formalFileType(), material.status(), material.entryLocator(),
                material.contentDigest(), material.version(), material.cannotConfirmReason(), nameRevision);
    }

    public AssignmentResponse assignment(MaterialAssignment value) {
        return new AssignmentResponse(value.materialId(), value.requirementId(), value.status());
    }

    public UploadConfirmationResponse upload(UploadConfirmation value) {
        return new UploadConfirmationResponse(
                value.uploadConfirmationId(), value.checklistSnapshotId(),
                value.materialSetRevision(), value.confirmedAt());
    }

    public JobResponse job(ReviewJob value) {
        JobResultRefResponse result = value.result() == null ? null
                : new JobResultRefResponse(value.result().resultType(), value.result().resultId());
        JobErrorResponse error = value.error() == null ? null
                : new JobErrorResponse(value.error().errorCode(), value.error().retryable(), value.error().message());
        return new JobResponse(
                value.jobId(), value.jobType(), value.status(),
                new ProgressResponse(value.progressCompleted(), value.progressTotal()),
                value.attempt(), result, error, value.createdAt(), value.startedAt(), value.finishedAt());
    }

    public ReportResponse report(String ownerId, String caseId, EffectiveReportView view) {
        ReviewReport report = view.published().report();
        Set<String> ignored = view.decisions().stream().map(FindingDecision::findingId).collect(java.util.stream.Collectors.toSet());
        List<FindingRow> rows = findingRows(ownerId, caseId, report.reportId());
        Map<String, FindingRow> byStableKey = new HashMap<>();
        rows.forEach(row -> byStableKey.put(row.stableKey(), row));
        List<FindingResponse> findings = new ArrayList<>();
        for (FindingDraft draft : view.published().findings()) {
            FindingRow row = byStableKey.get(draft.stableFindingKey());
            if (row == null) throw storage();
            List<EvidenceRow> evidenceRows = evidenceRows(ownerId, caseId, report.reportId(), row.findingId());
            Map<String, Deque<EvidenceRow>> ids = new HashMap<>();
            evidenceRows.forEach(item -> ids.computeIfAbsent(item.digest(), ignoredKey -> new ArrayDeque<>()).add(item));
            List<Map<String, Object>> rule = new ArrayList<>();
            List<Map<String, Object>> material = new ArrayList<>();
            for (EvidenceDraft evidence : draft.evidence()) {
                Deque<EvidenceRow> matching = ids.get(evidence.refDigest());
                if (matching == null || matching.isEmpty()) throw storage();
                Map<String, Object> projected = evidence(evidence, matching.removeFirst().evidenceId());
                (evidence.side() == EvidenceSide.RULE ? rule : material).add(projected);
            }
            findings.add(new FindingResponse(
                    row.findingId(), draft.type(), draft.cannotConfirmReason(), draft.requirementId(),
                    draft.materialId(), draft.message(), ignored.contains(row.findingId()),
                    List.copyOf(rule), List.copyOf(material)));
        }
        ReportSummaryResponse summary = new ReportSummaryResponse(
                report.missingCount(), report.fileNameNoncompliantCount(),
                report.cannotConfirmCount(), view.ignoredCount());
        return new ReportResponse(
                report.reportId(), report.caseId(), report.contractVersion(), report.engineVersion(),
                report.freshness(), report.assessment(),
                view.decisions().isEmpty() ? null : view.effectiveAssessment(), report.inputFingerprint(),
                report.checklistSnapshotId(), report.uploadConfirmationId(), report.basisRevision(),
                report.materialSetRevision(), report.assignmentRevision(), report.nameRevision(),
                report.baseReportId(), summary, List.copyOf(findings), report.createdAt());
    }

    private RemotePreviewSegmentResponse previewSegment(RemotePreviewSegment value) {
        return new RemotePreviewSegmentResponse(
                value.segmentId(), value.basisId(), locator(value.sourceLocator()),
                value.included(), value.redactedText());
    }

    private ChecklistItemResponse checklistItem(ChecklistDraftItem value) {
        return new ChecklistItemResponse(
                value.requirementId(), value.displayName(), value.enabled(), value.applicability(),
                value.minimumCount(), new FileNameRuleResponse(value.fileNameRule().kind(),
                value.fileNameRule().pattern(), value.fileNameRule().caseSensitive()),
                value.origin(), value.authorship(), value.itemVersion(),
                value.sourceRefs().stream().map(this::sourceRef).toList());
    }

    private ChecklistSourceRefResponse sourceRef(ChecklistSourceRef value) {
        return new ChecklistSourceRefResponse(
                value.basisId(), locator(value.sourceLocator()), value.quotedText());
    }

    private SourceLocatorResponse locator(OfficialBasisSourceLocator value) {
        return new SourceLocatorResponse(value.entryPath(), value.page(), value.sheet(),
                value.cellRange(), value.block(), value.entryDigest());
    }

    private DiagnosticResponse diagnostic(InputDiagnostic value) {
        Object locator = null;
        if (value.locatorJson() != null) {
            try {
                locator = JSON.readValue(value.locatorJson(), Object.class);
            } catch (Exception error) {
                throw storage();
            }
        }
        return new DiagnosticResponse(value.cannotConfirmReason(), value.failureStage(), locator);
    }

    private int snapshotItemCount(String snapshotJson) {
        try {
            return JSON.readTree(snapshotJson).path("items").size();
        } catch (Exception error) {
            throw storage();
        }
    }

    private List<ChecklistItemResponse> readSnapshotItems(String snapshotJson) {
        try {
            JsonNode root = JSON.readTree(snapshotJson);
            List<ChecklistItemResponse> items = new ArrayList<>();
            for (JsonNode node : root.path("items")) {
                JsonNode rule = node.path("fileNameRule");
                List<ChecklistSourceRefResponse> refs = new ArrayList<>();
                for (JsonNode ref : node.path("sourceRefs")) {
                    JsonNode loc = ref.path("sourceLocator");
                    refs.add(new ChecklistSourceRefResponse(
                            ref.path("basisId").textValue(),
                            new SourceLocatorResponse(text(loc, "entryPath"), integer(loc, "page"),
                                    text(loc, "sheet"), text(loc, "cellRange"), text(loc, "block"),
                                    text(loc, "entryDigest")), ref.path("quotedText").textValue()));
                }
                items.add(new ChecklistItemResponse(
                        node.path("requirementId").textValue(), node.path("displayName").textValue(),
                        node.path("enabled").booleanValue(), RequirementApplicability.valueOf(node.path("applicability").textValue()),
                        node.path("minimumCount").intValue(), new FileNameRuleResponse(
                        FileNameRuleKind.valueOf(rule.path("kind").textValue()), text(rule, "pattern"), bool(rule, "caseSensitive")),
                        RequirementOrigin.valueOf(node.path("origin").textValue()),
                        RequirementAuthorship.valueOf(node.path("authorship").textValue()),
                        node.path("itemVersion").longValue(), List.copyOf(refs)));
            }
            return List.copyOf(items);
        } catch (Exception error) {
            throw storage();
        }
    }

    private Map<String, Object> evidence(EvidenceDraft draft, String evidenceId) {
        Map<String, Object> value = draft.value();
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("kind", draft.kind());
        response.put("refId", evidenceId);
        switch (draft.kind()) {
            case OFFICIAL_BASIS_LOCATION -> {
                Object official = value.get("officialSourceRef");
                if (official instanceof Map<?, ?> source) {
                    response.put("basisId", source.get("basisId"));
                    response.put("sourceLocator", publicLocator(source));
                }
                response.put("requirementId", value.get("requirementId"));
            }
            case USER_RULE_REVISION -> {
                response.put("requirementId", value.get("requirementId"));
                response.put("checklistSnapshotId", value.get("checklistSnapshotId"));
                response.put("itemVersion", value.get("itemVersion"));
            }
            case GLOBAL_M0_POLICY -> response.put("ruleId", value.get("ruleId"));
            case MATERIAL_ITEM_SNAPSHOT -> {
                response.put("materialId", value.get("materialId"));
                response.put("submissionFileName", value.get("submissionFileName"));
                response.put("formalFileType", value.get("formalFileType"));
                response.put("contentDigest", value.get("contentDigest"));
                response.put("entryPath", value.get("entryPath"));
                response.put("entryIndex", value.get("entryIndex"));
            }
            case MATERIAL_SET_SNAPSHOT -> {
                response.put("uploadConfirmationId", value.get("uploadConfirmationId"));
                response.put("materialSetRevision", value.get("materialSetRevision"));
                response.put("expectedCount", value.get("expectedCount"));
                response.put("confirmedAvailableCount", value.get("confirmedAvailableCount"));
                Object raw = value.get("materials");
                List<Object> ids = new ArrayList<>();
                if (raw instanceof List<?> list) for (Object item : list) {
                    if (item instanceof Map<?, ?> map) ids.add(map.get("materialId"));
                }
                response.put("materialIds", List.copyOf(ids));
            }
        }
        return Collections.unmodifiableMap(response);
    }

    private Object publicLocator(Map<?, ?> source) {
        Object nested = source.get("sourceLocator");
        Map<?, ?> locator = nested instanceof Map<?, ?> map ? map : source;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("entryPath", "page", "sheet", "cellRange", "block", "entryDigest")) {
            result.put(key, locator.get(key));
        }
        return result;
    }

    private List<FindingRow> findingRows(String owner, String caseId, String reportId) {
        return jdbc.query("""
                SELECT finding.finding_id, finding.stable_finding_key
                FROM review_m0_finding finding
                JOIN review_m0_report report ON report.report_id=finding.report_id
                JOIN review_m0_case review_case ON review_case.case_id=report.case_id
                WHERE finding.report_id=? AND report.case_id=? AND review_case.user_id=?
                """, (rs, row) -> new FindingRow(rs.getString(1), rs.getString(2)), reportId, caseId, owner);
    }

    private List<EvidenceRow> evidenceRows(String owner, String caseId, String reportId, String findingId) {
        return jdbc.query("""
                SELECT evidence.evidence_id, evidence.ref_digest
                FROM review_m0_evidence_ref evidence
                JOIN review_m0_finding finding ON finding.finding_id=evidence.finding_id
                JOIN review_m0_report report ON report.report_id=finding.report_id
                JOIN review_m0_case review_case ON review_case.case_id=report.case_id
                WHERE finding.finding_id=? AND report.report_id=? AND report.case_id=? AND review_case.user_id=?
                ORDER BY evidence.evidence_id
                """, (rs, row) -> new EvidenceRow(rs.getString(1), rs.getString(2)),
                findingId, reportId, caseId, owner);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.textValue();
    }

    private static Integer integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.intValue();
    }

    private static Boolean bool(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.booleanValue();
    }

    private static ReviewM0Exception storage() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                "Stored Review M0 projection is unavailable");
    }

    private record FindingRow(String findingId, String stableKey) {}
    private record EvidenceRow(String evidenceId, String digest) {}
}
