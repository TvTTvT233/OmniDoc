package com.javaee.aiservice.review.m0.checklist;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.FileNameRule;
import com.javaee.aiservice.review.m0.model.FileNameRuleKind;
import com.javaee.aiservice.review.m0.model.OfficialBasis;
import com.javaee.aiservice.review.m0.model.OfficialBasisRole;
import com.javaee.aiservice.review.m0.model.OfficialBasisStatus;
import com.javaee.aiservice.review.m0.model.RequirementApplicability;
import com.javaee.aiservice.review.m0.model.RequirementAuthorship;
import com.javaee.aiservice.review.m0.model.RequirementOrigin;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class JdbcChecklistStore {
    private static final String BASIS_COLUMNS = """
            basis.basis_id, basis.case_id, basis.file_id, basis.role, basis.status,
            basis.original_file_name_snapshot, basis.declared_content_type, basis.file_size_bytes,
            basis.file_digest, basis.storage_version, basis.cannot_confirm_reason, basis.failure_stage,
            basis.attached_basis_revision, basis.detached_basis_revision, basis.version,
            basis.created_at, basis.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final M0IdGenerator ids;

    public JdbcChecklistStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            M0IdGenerator ids
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public OfficialBasis insertBasisOwned(
            String ownerId,
            String caseId,
            OfficialBasisAttachment attachment,
            long attachedBasisRevision,
            Instant now
    ) {
        requireOwnedCase(ownerId, caseId);
        try {
            jdbcTemplate.update("""
                            INSERT INTO review_m0_official_basis(
                                basis_id, case_id, file_id, role, status, original_file_name_snapshot,
                                declared_content_type, file_size_bytes, file_digest, storage_version,
                                cannot_confirm_reason, failure_stage, attached_basis_revision,
                                detached_basis_revision, version, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, 'ATTACHED', ?, ?, ?, ?, ?, NULL, NULL, ?, NULL, 0, ?, ?)
                            """,
                    attachment.basisId(), caseId, attachment.fileId(), attachment.role().name(),
                    attachment.originalFileNameSnapshot(), attachment.declaredContentType(),
                    attachment.fileSizeBytes(), attachment.fileDigest(), attachment.storageVersion(),
                    attachedBasisRevision, timestamp(now), timestamp(now));
        } catch (DataIntegrityViolationException error) {
            throw conflict("Official basis already exists");
        }
        return findBasisOwned(ownerId, caseId, attachment.basisId()).orElseThrow();
    }

    public Optional<OfficialBasis> findBasisOwned(String ownerId, String caseId, String basisId) {
        return jdbcTemplate.query("SELECT " + BASIS_COLUMNS + " FROM review_m0_official_basis basis "
                        + "JOIN review_m0_case review_case ON review_case.case_id = basis.case_id "
                        + "WHERE basis.basis_id = ? AND basis.case_id = ? AND review_case.user_id = ?",
                this::mapBasis, basisId, caseId, ownerId).stream().findFirst();
    }

    public List<OfficialBasis> listBasesOwned(String ownerId, String caseId) {
        requireOwnedCase(ownerId, caseId);
        return jdbcTemplate.query("SELECT " + BASIS_COLUMNS + " FROM review_m0_official_basis basis "
                        + "JOIN review_m0_case review_case ON review_case.case_id = basis.case_id "
                        + "WHERE basis.case_id = ? AND review_case.user_id = ? ORDER BY basis.basis_id",
                this::mapBasis, caseId, ownerId);
    }

    public OfficialBasis updateBasisRoleOwned(
            String ownerId, String caseId, String basisId, long expectedVersion,
            OfficialBasisRole role, Instant now
    ) {
        int updated = jdbcTemplate.update("""
                        UPDATE review_m0_official_basis basis
                        SET role = ?, version = version + 1, updated_at = ?
                        WHERE basis_id = ? AND case_id = ? AND version = ? AND status <> 'DETACHED'
                          AND EXISTS (SELECT 1 FROM review_m0_case review_case
                                      WHERE review_case.case_id = basis.case_id AND review_case.user_id = ?)
                        """,
                role.name(), timestamp(now), basisId, caseId, expectedVersion, ownerId);
        if (updated != 1) {
            requireBasisOwned(ownerId, caseId, basisId);
            throw conflict("Official basis changed concurrently");
        }
        return requireBasisOwned(ownerId, caseId, basisId);
    }

    public OfficialBasis updateBasisStatusOwned(
            String ownerId,
            String caseId,
            String basisId,
            long expectedVersion,
            OfficialBasisStatus expectedStatus,
            OfficialBasisStatus status,
            CannotConfirmReason reason,
            String failureStage,
            Long detachedBasisRevision,
            Instant now
    ) {
        int updated = jdbcTemplate.update("""
                        UPDATE review_m0_official_basis basis
                        SET status = ?, cannot_confirm_reason = ?, failure_stage = ?,
                            detached_basis_revision = ?, version = version + 1, updated_at = ?
                        WHERE basis_id = ? AND case_id = ? AND version = ? AND status = ?
                          AND EXISTS (SELECT 1 FROM review_m0_case review_case
                                      WHERE review_case.case_id = basis.case_id AND review_case.user_id = ?)
                        """,
                status.name(), reason == null ? null : reason.name(), failureStage, detachedBasisRevision,
                timestamp(now), basisId, caseId, expectedVersion, expectedStatus.name(), ownerId);
        if (updated != 1) {
            requireBasisOwned(ownerId, caseId, basisId);
            throw conflict("Official basis changed concurrently");
        }
        return requireBasisOwned(ownerId, caseId, basisId);
    }

    public void insertBasisDiagnosticOwned(
            String ownerId,
            String caseId,
            String basisId,
            CannotConfirmReason reason,
            String failureStage,
            Instant now
    ) {
        requireBasisOwned(ownerId, caseId, basisId);
        jdbcTemplate.update("""
                        INSERT INTO review_m0_input_diagnostic(
                            diagnostic_id, case_id, subject_type, basis_id, source_id, material_id,
                            cannot_confirm_reason, failure_stage, locator_json, evidence_digest, created_at
                        ) VALUES (?, ?, 'OFFICIAL_BASIS', ?, NULL, NULL, ?, ?, NULL, NULL, ?)
                        """,
                ids.next("diag_"), caseId, basisId, reason.name(), failureStage, timestamp(now));
    }

    public Optional<ChecklistDraftView> findLatestDraftOwned(String ownerId, String caseId) {
        return findRevisionOwned(ownerId, caseId, true);
    }

    public Optional<ChecklistDraftView> findLatestRevisionOwned(String ownerId, String caseId) {
        return findRevisionOwned(ownerId, caseId, false);
    }

    public Optional<ChecklistDraftView> findRevisionByIdOwned(
            String ownerId, String caseId, String checklistRevisionId
    ) {
        List<RevisionRow> revisions = jdbcTemplate.query("""
                        SELECT revision.checklist_revision_id, revision.case_id, revision.lifecycle,
                               revision.basis_revision, revision.revision_number, revision.draft_digest,
                               revision.extraction_job_id, revision.created_by, revision.created_at
                        FROM review_m0_checklist_revision revision
                        JOIN review_m0_case review_case ON review_case.case_id = revision.case_id
                        WHERE revision.checklist_revision_id = ? AND revision.case_id = ?
                          AND review_case.user_id = ?
                        """, this::mapRevision, checklistRevisionId, caseId, ownerId);
        return revisions.stream().findFirst().map(this::loadView);
    }

    public Optional<ChecklistDraftView> findDraftByExtractionJobOwned(
            String ownerId, String caseId, String extractionJobId
    ) {
        List<RevisionRow> revisions = jdbcTemplate.query("""
                        SELECT revision.checklist_revision_id, revision.case_id, revision.lifecycle,
                               revision.basis_revision, revision.revision_number, revision.draft_digest,
                               revision.extraction_job_id, revision.created_by, revision.created_at
                        FROM review_m0_checklist_revision revision
                        JOIN review_m0_case review_case ON review_case.case_id = revision.case_id
                        WHERE revision.extraction_job_id = ? AND revision.case_id = ?
                          AND review_case.user_id = ?
                        ORDER BY revision.revision_number DESC LIMIT 1
                        """, this::mapRevision, extractionJobId, caseId, ownerId);
        return revisions.stream().findFirst().map(this::loadView);
    }

    public ChecklistDraftView saveDraftOwned(
            String ownerId,
            String caseId,
            long expectedBasisRevision,
            String createdBy,
            String extractionJobId,
            List<ChecklistDraftItem> items,
            Instant now
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            long currentBasisRevision = lockOwnedCase(ownerId, caseId);
            if (currentBasisRevision != expectedBasisRevision) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_BASIS_REVISION_STALE,
                        "Checklist draft basis revision is stale");
            }
            List<ChecklistDraftItem> stableItems = ChecklistJson.sorted(items);
            long revisionNumber = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(revision_number), 0) + 1 FROM review_m0_checklist_revision WHERE case_id = ?",
                    Long.class, caseId);
            String checklistRevisionId = ids.next("clrev_");
            String draftDigest = ChecklistJson.draftDigest(caseId, expectedBasisRevision, stableItems);
            jdbcTemplate.update("""
                            INSERT INTO review_m0_checklist_revision(
                                checklist_revision_id, case_id, lifecycle, basis_revision, revision_number,
                                draft_digest, extraction_job_id, created_by, created_at
                            ) VALUES (?, ?, 'CANDIDATE_DRAFT', ?, ?, ?, ?, ?, ?)
                            """,
                    checklistRevisionId, caseId, expectedBasisRevision, revisionNumber, draftDigest,
                    extractionJobId, createdBy, timestamp(now));
            for (ChecklistDraftItem item : stableItems) {
                ensureRequirementIdentity(caseId, item, now);
                jdbcTemplate.update("""
                                INSERT INTO review_m0_requirement_revision(
                                    requirement_revision_id, requirement_id, checklist_revision_id,
                                    display_name, enabled, applicability, minimum_count,
                                    file_name_rule_kind, file_name_rule_pattern, file_name_rule_case_sensitive,
                                    authorship, source_refs_json, item_version, created_at
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        ids.next("reqrev_"), item.requirementId(), checklistRevisionId,
                        item.displayName(), item.enabled(), item.applicability().name(), item.minimumCount(),
                        item.fileNameRule().kind().name(), item.fileNameRule().pattern(),
                        item.fileNameRule().caseSensitive(), item.authorship().name(),
                        ChecklistJson.sourceRefs(item.sourceRefs()), item.itemVersion(), timestamp(now));
            }
            return new ChecklistDraftView(
                    checklistRevisionId, caseId, ChecklistLifecycle.CANDIDATE_DRAFT,
                    expectedBasisRevision, revisionNumber, draftDigest, extractionJobId,
                    createdBy, now, stableItems);
        }));
    }

    private Optional<ChecklistDraftView> findRevisionOwned(String ownerId, String caseId, boolean draftOnly) {
        String lifecycle = draftOnly ? " AND revision.lifecycle = 'CANDIDATE_DRAFT'" : "";
        List<RevisionRow> revisions = jdbcTemplate.query("""
                        SELECT revision.checklist_revision_id, revision.case_id, revision.lifecycle,
                               revision.basis_revision, revision.revision_number, revision.draft_digest,
                               revision.extraction_job_id, revision.created_by, revision.created_at
                        FROM review_m0_checklist_revision revision
                        JOIN review_m0_case review_case ON review_case.case_id = revision.case_id
                        WHERE revision.case_id = ? AND review_case.user_id = ?
                        """ + lifecycle + " ORDER BY revision.revision_number DESC LIMIT 1",
                this::mapRevision, caseId, ownerId);
        return revisions.stream().findFirst().map(this::loadView);
    }

    private ChecklistDraftView loadView(RevisionRow revision) {
        List<ChecklistDraftItem> items = jdbcTemplate.query("""
                        SELECT requirement.requirement_id, requirement.origin,
                               item.display_name, item.enabled, item.applicability, item.minimum_count,
                               item.file_name_rule_kind, item.file_name_rule_pattern,
                               item.file_name_rule_case_sensitive, item.authorship,
                               item.source_refs_json, item.item_version
                        FROM review_m0_requirement_revision item
                        JOIN review_m0_requirement requirement
                          ON requirement.requirement_id = item.requirement_id
                        WHERE item.checklist_revision_id = ? AND requirement.case_id = ?
                        ORDER BY requirement.requirement_id
                        """, this::mapItem, revision.checklistRevisionId(), revision.caseId());
        String actualDigest = ChecklistJson.draftDigest(revision.caseId(), revision.basisRevision(), items);
        if (!actualDigest.equals(revision.draftDigest())) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                    "Stored checklist draft integrity check failed");
        }
        return new ChecklistDraftView(
                revision.checklistRevisionId(), revision.caseId(), revision.lifecycle(),
                revision.basisRevision(), revision.revisionNumber(), revision.draftDigest(),
                revision.extractionJobId(), revision.createdBy(), revision.createdAt(), items);
    }

    private void ensureRequirementIdentity(String caseId, ChecklistDraftItem item, Instant now) {
        List<RequirementIdentity> identities = jdbcTemplate.query("""
                        SELECT case_id, origin FROM review_m0_requirement WHERE requirement_id = ?
                        """, (resultSet, rowNumber) -> new RequirementIdentity(
                        resultSet.getString("case_id"),
                        RequirementOrigin.valueOf(resultSet.getString("origin"))), item.requirementId());
        if (identities.isEmpty()) {
            jdbcTemplate.update("""
                            INSERT INTO review_m0_requirement(requirement_id, case_id, origin, created_at)
                            VALUES (?, ?, ?, ?)
                            """, item.requirementId(), caseId, item.origin().name(), timestamp(now));
            return;
        }
        RequirementIdentity identity = identities.get(0);
        if (!identity.caseId().equals(caseId) || identity.origin() != item.origin()) {
            throw conflict("Checklist requirement identity cannot change case or origin");
        }
    }

    private long lockOwnedCase(String ownerId, String caseId) {
        List<Long> revisions = jdbcTemplate.query("""
                        SELECT basis_revision FROM review_m0_case
                        WHERE case_id = ? AND user_id = ? FOR UPDATE
                        """, (resultSet, rowNumber) -> resultSet.getLong("basis_revision"), caseId, ownerId);
        if (revisions.isEmpty()) {
            throw notFound(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND, "Review case was not found");
        }
        return revisions.get(0);
    }

    private void requireOwnedCase(String ownerId, String caseId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_m0_case WHERE case_id = ? AND user_id = ?",
                Integer.class, caseId, ownerId);
        if (count == null || count != 1) {
            throw notFound(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND, "Review case was not found");
        }
    }

    private OfficialBasis requireBasisOwned(String ownerId, String caseId, String basisId) {
        return findBasisOwned(ownerId, caseId, basisId).orElseThrow(() ->
                notFound(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, "Official basis was not found"));
    }

    private OfficialBasis mapBasis(ResultSet resultSet, int rowNumber) throws SQLException {
        String reason = resultSet.getString("cannot_confirm_reason");
        long detached = resultSet.getLong("detached_basis_revision");
        return new OfficialBasis(
                resultSet.getString("basis_id"), resultSet.getString("case_id"),
                resultSet.getString("file_id"), OfficialBasisRole.valueOf(resultSet.getString("role")),
                OfficialBasisStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("original_file_name_snapshot"),
                resultSet.getString("declared_content_type"), resultSet.getLong("file_size_bytes"),
                resultSet.getString("file_digest"), resultSet.getString("storage_version"),
                reason == null ? null : CannotConfirmReason.valueOf(reason), resultSet.getString("failure_stage"),
                resultSet.getLong("attached_basis_revision"), resultSet.wasNull() ? null : detached,
                resultSet.getLong("version"), instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at")));
    }

    private RevisionRow mapRevision(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RevisionRow(
                resultSet.getString("checklist_revision_id"), resultSet.getString("case_id"),
                ChecklistLifecycle.valueOf(resultSet.getString("lifecycle")),
                resultSet.getLong("basis_revision"), resultSet.getLong("revision_number"),
                resultSet.getString("draft_digest"), resultSet.getString("extraction_job_id"),
                resultSet.getString("created_by"), instant(resultSet.getTimestamp("created_at")));
    }

    private ChecklistDraftItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        String pattern = resultSet.getString("file_name_rule_pattern");
        Boolean caseSensitive = resultSet.getObject("file_name_rule_case_sensitive", Boolean.class);
        return new ChecklistDraftItem(
                resultSet.getString("requirement_id"), resultSet.getString("display_name"),
                resultSet.getBoolean("enabled"),
                RequirementApplicability.valueOf(resultSet.getString("applicability")),
                resultSet.getInt("minimum_count"),
                new FileNameRule(
                        FileNameRuleKind.valueOf(resultSet.getString("file_name_rule_kind")),
                        pattern, caseSensitive),
                RequirementOrigin.valueOf(resultSet.getString("origin")),
                RequirementAuthorship.valueOf(resultSet.getString("authorship")),
                ChecklistJson.readSourceRefs(resultSet.getString("source_refs_json")),
                resultSet.getLong("item_version"));
    }

    private static ReviewM0Exception conflict(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT, message);
    }

    private static ReviewM0Exception notFound(ReviewM0ErrorCode code, String message) {
        return new ReviewM0Exception(code, message);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    private record RevisionRow(
            String checklistRevisionId, String caseId, ChecklistLifecycle lifecycle,
            long basisRevision, long revisionNumber, String draftDigest,
            String extractionJobId, String createdBy, Instant createdAt
    ) {
    }

    private record RequirementIdentity(String caseId, RequirementOrigin origin) {
    }
}
