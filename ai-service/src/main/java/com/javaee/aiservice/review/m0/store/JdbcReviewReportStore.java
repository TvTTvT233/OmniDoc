package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.job.ReviewM0JobStore;
import com.javaee.aiservice.review.m0.model.EvidenceRef;
import com.javaee.aiservice.review.m0.model.CanonicalJsonIntegrity;
import com.javaee.aiservice.review.m0.model.EvidenceSide;
import com.javaee.aiservice.review.m0.model.FindingType;
import com.javaee.aiservice.review.m0.model.ReportBundle;
import com.javaee.aiservice.review.m0.model.ReviewAssessment;
import com.javaee.aiservice.review.m0.model.ReviewFinding;
import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobResultRef;
import com.javaee.aiservice.review.m0.model.ReviewReport;
import com.javaee.aiservice.review.m0.model.ReviewReportFreshness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcReviewReportStore implements ReviewReportStore {
    private static final String REPORT_COLUMNS = """
            report_id, case_id, job_id, contract_version, engine_version, freshness, assessment,
            input_fingerprint, checklist_snapshot_id, upload_confirmation_id, basis_revision,
            material_set_revision, assignment_revision, name_revision, base_report_id,
            missing_count, file_name_noncompliant_count, cannot_confirm_count,
            created_at, outdated_at, outdated_reason
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ReviewM0JobStore jobStore;
    private final RowMapper<ReviewReport> mapper = this::map;

    public JdbcReviewReportStore(JdbcTemplate jdbcTemplate, ReviewM0JobStore jobStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobStore = jobStore;
    }

    @Override
    public Optional<ReviewReport> findOwned(String ownerId, String caseId, String reportId) {
        JdbcReviewCaseStore.requireOwner(ownerId);
        return jdbcTemplate.query(
                "SELECT r.* FROM review_m0_report r JOIN review_m0_case c ON c.case_id = r.case_id "
                        + "WHERE r.report_id = ? AND r.case_id = ? AND c.user_id = ?",
                mapper, reportId, caseId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<ReviewReport> findCurrentOwned(String ownerId, String caseId) {
        JdbcReviewCaseStore.requireOwner(ownerId);
        return jdbcTemplate.query(
                "SELECT r.* FROM review_m0_report r JOIN review_m0_case c ON c.case_id = r.case_id "
                        + "WHERE r.case_id = ? AND c.user_id = ? AND r.freshness = 'CURRENT'",
                mapper, caseId, ownerId).stream().findFirst();
    }

    @Override
    public ReviewJob publishOwned(String ownerId, String workerId, Instant now, ReportBundle bundle) {
        validateBundle(bundle);
        ReviewReport report = bundle.report();
        return jobStore.completeWithResult(report.jobId(), workerId, now, () -> {
            CaseInput current = requireCurrentCaseInput(ownerId, report.caseId());
            if (current.basisRevision != report.basisRevision()
                    || current.materialSetRevision != report.materialSetRevision()
                    || current.assignmentRevision != report.assignmentRevision()
                    || current.nameRevision != report.nameRevision()
                    || !report.checklistSnapshotId().equals(current.currentChecklistSnapshotId)) {
                throw StoreErrors.versionConflict("Report input revisions are no longer current");
            }
            List<ReviewReport> sameInput = jdbcTemplate.query(
                    "SELECT " + REPORT_COLUMNS + " FROM review_m0_report "
                            + "WHERE case_id = ? AND input_fingerprint = ?",
                    mapper, report.caseId(), report.inputFingerprint());
            if (!sameInput.isEmpty()) {
                ReviewReport existing = sameInput.get(0);
                if (existing.freshness() != ReviewReportFreshness.CURRENT
                        || !existing.reportId().equals(current.currentReportId)) {
                    throw StoreErrors.versionConflict("An OUTDATED report cannot be restored to CURRENT");
                }
                return new ReviewJobResultRef("REPORT", existing.reportId());
            }
            if (current.currentReportId != null) {
                int expired = jdbcTemplate.update("""
                                UPDATE review_m0_report
                                SET freshness = 'OUTDATED', outdated_at = ?, outdated_reason = 'NEW_REPORT_PUBLISHED'
                                WHERE report_id = ? AND case_id = ? AND freshness = 'CURRENT'
                                """,
                        timestamp(now), current.currentReportId, report.caseId());
                if (expired != 1) {
                    throw StoreErrors.versionConflict("Previous CURRENT report could not be expired");
                }
            }
            insertReport(report);
            bundle.findings().forEach(this::insertFinding);
            bundle.evidence().forEach(this::insertEvidence);
            int pointed = jdbcTemplate.update("""
                            UPDATE review_m0_case
                            SET current_report_id = ?, version = version + 1, updated_at = ?
                            WHERE case_id = ? AND user_id = ? AND version = ?
                            """,
                    report.reportId(), timestamp(now), report.caseId(), ownerId, current.version);
            if (pointed != 1) {
                throw StoreErrors.versionConflict("Review case changed while publishing report");
            }
            return new ReviewJobResultRef("REPORT", report.reportId());
        });
    }

    private CaseInput requireCurrentCaseInput(String ownerId, String caseId) {
        JdbcReviewCaseStore.requireOwner(ownerId);
        List<CaseInput> rows = jdbcTemplate.query("""
                        SELECT basis_revision, material_set_revision, assignment_revision, name_revision,
                               current_checklist_snapshot_id, current_report_id, version
                        FROM review_m0_case WHERE case_id = ? AND user_id = ? FOR UPDATE
                        """,
                (resultSet, rowNumber) -> new CaseInput(
                        resultSet.getLong("basis_revision"), resultSet.getLong("material_set_revision"),
                        resultSet.getLong("assignment_revision"), resultSet.getLong("name_revision"),
                        resultSet.getString("current_checklist_snapshot_id"),
                        resultSet.getString("current_report_id"), resultSet.getLong("version")),
                caseId, ownerId);
        if (rows.isEmpty()) {
            throw StoreErrors.caseNotFound();
        }
        return rows.get(0);
    }

    private void insertReport(ReviewReport report) {
        jdbcTemplate.update("""
                        INSERT INTO review_m0_report(
                            report_id, case_id, job_id, contract_version, engine_version, freshness, assessment,
                            input_fingerprint, checklist_snapshot_id, upload_confirmation_id, basis_revision,
                            material_set_revision, assignment_revision, name_revision, base_report_id,
                            missing_count, file_name_noncompliant_count, cannot_confirm_count,
                            created_at, outdated_at, outdated_reason
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                report.reportId(), report.caseId(), report.jobId(), report.contractVersion(), report.engineVersion(),
                report.freshness().name(), report.assessment().name(), report.inputFingerprint(),
                report.checklistSnapshotId(), report.uploadConfirmationId(), report.basisRevision(),
                report.materialSetRevision(), report.assignmentRevision(), report.nameRevision(), report.baseReportId(),
                report.missingCount(), report.fileNameNoncompliantCount(), report.cannotConfirmCount(),
                timestamp(report.createdAt()), timestamp(report.outdatedAt()), report.outdatedReason());
    }

    private void insertFinding(ReviewFinding finding) {
        jdbcTemplate.update("""
                        INSERT INTO review_m0_finding(
                            finding_id, report_id, finding_type, cannot_confirm_reason, requirement_id,
                            material_id, stable_finding_key, message, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                finding.findingId(), finding.reportId(), finding.findingType().name(),
                finding.cannotConfirmReason() == null ? null : finding.cannotConfirmReason().name(),
                finding.requirementId(), finding.materialId(), finding.stableFindingKey(), finding.message(),
                timestamp(finding.createdAt()));
    }

    private void insertEvidence(EvidenceRef evidence) {
        CanonicalJsonIntegrity.requireValid(evidence.refJson(), evidence.refDigest(), "Evidence reference");
        jdbcTemplate.update("""
                        INSERT INTO review_m0_evidence_ref(
                            evidence_id, finding_id, evidence_side, evidence_kind, ref_json, ref_digest, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                evidence.evidenceId(), evidence.findingId(), evidence.evidenceSide().name(),
                evidence.evidenceKind().name(), evidence.refJson(), evidence.refDigest(),
                timestamp(evidence.createdAt()));
    }

    private static void validateBundle(ReportBundle bundle) {
        ReviewReport report = bundle.report();
        if (report.freshness() != ReviewReportFreshness.CURRENT) {
            throw StoreErrors.versionConflict("Only a new CURRENT report can be published");
        }
        long missing = bundle.findings().stream().filter(item -> item.findingType() == FindingType.MISSING).count();
        long names = bundle.findings().stream()
                .filter(item -> item.findingType() == FindingType.FILE_NAME_NONCOMPLIANT).count();
        long cannot = bundle.findings().stream()
                .filter(item -> item.findingType() == FindingType.CANNOT_CONFIRM).count();
        if (missing != report.missingCount() || names != report.fileNameNoncompliantCount()
                || cannot != report.cannotConfirmCount()) {
            throw StoreErrors.versionConflict("Report counters do not match immutable findings");
        }
        if (report.assessment() == ReviewAssessment.M0_READY && !bundle.findings().isEmpty()) {
            throw StoreErrors.versionConflict("M0_READY cannot publish findings");
        }
        Map<String, EnumSet<EvidenceSide>> sides = new HashMap<>();
        for (EvidenceRef evidence : bundle.evidence()) {
            sides.computeIfAbsent(evidence.findingId(), ignored -> EnumSet.noneOf(EvidenceSide.class))
                    .add(evidence.evidenceSide());
        }
        for (ReviewFinding finding : bundle.findings()) {
            if (!finding.reportId().equals(report.reportId())) {
                throw StoreErrors.versionConflict("Finding belongs to a different report");
            }
            if ((finding.findingType() == FindingType.MISSING
                    || finding.findingType() == FindingType.FILE_NAME_NONCOMPLIANT)
                    && !sides.getOrDefault(finding.findingId(), EnumSet.noneOf(EvidenceSide.class))
                    .containsAll(EnumSet.allOf(EvidenceSide.class))) {
                throw StoreErrors.versionConflict("Deterministic findings require rule and material evidence");
            }
        }
        for (EvidenceRef evidence : bundle.evidence()) {
            if (bundle.findings().stream().noneMatch(finding -> finding.findingId().equals(evidence.findingId()))) {
                throw StoreErrors.versionConflict("Evidence refers to a finding outside the report bundle");
            }
        }
    }

    private ReviewReport map(ResultSet resultSet, int rowNumber) throws SQLException {
        String cannotReason = resultSet.getString("outdated_reason");
        return new ReviewReport(
                resultSet.getString("report_id"), resultSet.getString("case_id"), resultSet.getString("job_id"),
                resultSet.getString("contract_version"), resultSet.getString("engine_version"),
                ReviewReportFreshness.valueOf(resultSet.getString("freshness")),
                ReviewAssessment.valueOf(resultSet.getString("assessment")),
                resultSet.getString("input_fingerprint"), resultSet.getString("checklist_snapshot_id"),
                resultSet.getString("upload_confirmation_id"), resultSet.getLong("basis_revision"),
                resultSet.getLong("material_set_revision"), resultSet.getLong("assignment_revision"),
                resultSet.getLong("name_revision"), resultSet.getString("base_report_id"),
                resultSet.getInt("missing_count"), resultSet.getInt("file_name_noncompliant_count"),
                resultSet.getInt("cannot_confirm_count"),
                JdbcReviewCaseStore.instant(resultSet.getTimestamp("created_at")),
                JdbcReviewCaseStore.instant(resultSet.getTimestamp("outdated_at")), cannotReason);
    }

    private static Timestamp timestamp(Instant instant) {
        return JdbcReviewCaseStore.timestamp(instant);
    }

    private record CaseInput(
            long basisRevision,
            long materialSetRevision,
            long assignmentRevision,
            long nameRevision,
            String currentChecklistSnapshotId,
            String currentReportId,
            long version
    ) {
    }
}
