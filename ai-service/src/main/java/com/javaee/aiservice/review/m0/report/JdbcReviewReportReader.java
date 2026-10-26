package com.javaee.aiservice.review.m0.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.evidence.EvidenceDraft;
import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.model.CanonicalJson;
import com.javaee.aiservice.review.m0.model.CanonicalJsonIntegrity;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.EvidenceKind;
import com.javaee.aiservice.review.m0.model.EvidenceSide;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.ReviewFinding;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.review.FindingDraft;
import com.javaee.aiservice.review.m0.review.ReviewJobRequestEnvelope;
import com.javaee.aiservice.review.m0.store.ReviewReportStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JdbcReviewReportReader implements ReviewReportReader {
    private static final ObjectMapper READER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final ReviewReportStore reportStore;
    private final ReviewM0JobStore jobStore;

    public JdbcReviewReportReader(
            JdbcTemplate jdbcTemplate,
            ReviewReportStore reportStore,
            ReviewM0JobStore jobStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.reportStore = reportStore;
        this.jobStore = jobStore;
    }

    @Override
    public Optional<PublishedReview> findOwned(String ownerId, String caseId, String reportId) {
        Optional<ReviewReport> report = reportStore.findOwned(ownerId, caseId, reportId);
        if (report.isEmpty()) return Optional.empty();
        ReviewReport value = report.get();
        ReviewJob job = jobStore.findOwned(ownerId, caseId, value.jobId())
                .orElseThrow(() -> storage("Report Job is unavailable"));
        ReviewJobRequestEnvelope envelope = ReviewJobRequestEnvelope.readCanonical(job.requestJson());
        if (!envelope.input().fingerprint().sha256().equals(value.inputFingerprint())
                || !envelope.input().caseId().equals(caseId)) {
            throw storage("Report input manifest does not match the immutable report");
        }
        List<ReviewFinding> findings = jdbcTemplate.query("""
                        SELECT finding.finding_id, finding.report_id, finding.finding_type,
                               finding.cannot_confirm_reason, finding.requirement_id, finding.material_id,
                               finding.stable_finding_key, finding.message, finding.created_at
                        FROM review_m0_finding finding
                        JOIN review_m0_report report ON report.report_id = finding.report_id
                        JOIN review_m0_case review_case ON review_case.case_id = report.case_id
                        WHERE finding.report_id = ? AND report.case_id = ? AND review_case.user_id = ?
                        ORDER BY finding.stable_finding_key
                        """,
                (rs, row) -> new ReviewFinding(
                        rs.getString("finding_id"), rs.getString("report_id"),
                        FindingType.valueOf(rs.getString("finding_type")),
                        rs.getString("cannot_confirm_reason") == null ? null
                                : CannotConfirmReason.valueOf(rs.getString("cannot_confirm_reason")),
                        rs.getString("requirement_id"), rs.getString("material_id"),
                        rs.getString("stable_finding_key"), rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant()),
                reportId, caseId, ownerId);
        Map<String, List<EvidenceDraft>> evidence = new HashMap<>();
        jdbcTemplate.query("""
                        SELECT evidence.finding_id, evidence.evidence_side, evidence.evidence_kind,
                               evidence.ref_json, evidence.ref_digest
                        FROM review_m0_evidence_ref evidence
                        JOIN review_m0_finding finding ON finding.finding_id = evidence.finding_id
                        JOIN review_m0_report report ON report.report_id = finding.report_id
                        JOIN review_m0_case review_case ON review_case.case_id = report.case_id
                        WHERE report.report_id = ? AND report.case_id = ? AND review_case.user_id = ?
                        ORDER BY evidence.evidence_id
                        """, rs -> {
                    String json = rs.getString("ref_json");
                    String digest = rs.getString("ref_digest");
                    CanonicalJsonIntegrity.requireValid(json, digest, "Evidence reference");
                    Map<String, Object> ref;
                    try {
                        ref = READER.convertValue(READER.readTree(json), Map.class);
                    } catch (Exception error) {
                        throw storage("Evidence reference cannot be decoded");
                    }
                    if (!CanonicalJson.write(ref).equals(json)) {
                        throw storage("Evidence reference is not canonical");
                    }
                    evidence.computeIfAbsent(rs.getString("finding_id"), ignored -> new ArrayList<>())
                            .add(new EvidenceDraft(
                                    EvidenceSide.valueOf(rs.getString("evidence_side")),
                                    EvidenceKind.valueOf(rs.getString("evidence_kind")), ref));
                }, reportId, caseId, ownerId);
        List<FindingDraft> drafts = findings.stream().map(finding -> new FindingDraft(
                        finding.findingType(), finding.cannotConfirmReason(), finding.requirementId(),
                        finding.materialId(), finding.stableFindingKey(), finding.message(),
                        evidence.getOrDefault(finding.findingId(), List.of())))
                .sorted(Comparator.comparing(FindingDraft::normalizedKey))
                .toList();
        return Optional.of(new PublishedReview(value, drafts, envelope.input()));
    }

    private static ReviewM0Exception storage(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE, message);
    }
}
