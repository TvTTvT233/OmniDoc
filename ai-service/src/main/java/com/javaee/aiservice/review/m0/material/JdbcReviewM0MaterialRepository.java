package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.model.AssignmentStatus;
import com.javaee.aiservice.review.m0.model.CannotConfirmReason;
import com.javaee.aiservice.review.m0.model.DiagnosticSubjectType;
import com.javaee.aiservice.review.m0.model.FormalFileType;
import com.javaee.aiservice.review.m0.model.InputDiagnostic;
import com.javaee.aiservice.review.m0.model.MaterialAssignment;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.MaterialStatus;
import com.javaee.aiservice.review.m0.model.ReviewM0KeyDigests;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;
import com.javaee.aiservice.review.m0.model.SubmissionSourceStatus;
import com.javaee.aiservice.review.m0.model.UploadConfirmation;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcReviewM0MaterialRepository implements ReviewM0MaterialRepository {
    private static final String SOURCE_COLUMNS = """
            s.source_id, s.case_id, s.file_id, s.original_file_name_snapshot,
            s.declared_content_type, s.formal_file_type, s.status, s.file_size_bytes,
            s.file_digest, s.storage_version, s.attached_material_set_revision,
            s.removed_material_set_revision, s.version, s.created_at, s.updated_at
            """;
    private static final String MATERIAL_COLUMNS = """
            m.material_id, m.case_id, m.source_id, m.kind, m.normalized_entry_path,
            m.original_entry_name, m.entry_index, m.content_digest, m.formal_file_type,
            m.submission_file_name, m.normalized_submission_file_name, m.status,
            m.cannot_confirm_reason, m.failure_stage, m.material_set_revision,
            m.name_revision, m.version, m.created_at, m.updated_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcReviewM0MaterialRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public List<SubmissionSource> listSourcesOwned(String ownerId, String caseId) {
        requireOwnedCase(ownerId, caseId);
        return jdbc.query("SELECT " + SOURCE_COLUMNS + " FROM review_m0_submission_source s "
                        + "JOIN review_m0_case c ON c.case_id=s.case_id "
                        + "WHERE s.case_id=? AND c.user_id=? ORDER BY s.source_id",
                this::mapSource, caseId, ownerId);
    }

    @Override
    public List<SubmissionMaterial> listMaterialsOwned(String ownerId, String caseId) {
        requireOwnedCase(ownerId, caseId);
        return jdbc.query("SELECT " + MATERIAL_COLUMNS + " FROM review_m0_material m "
                        + "JOIN review_m0_case c ON c.case_id=m.case_id "
                        + "WHERE m.case_id=? AND c.user_id=? ORDER BY m.material_id",
                this::mapMaterial, caseId, ownerId);
    }

    @Override
    public List<InputDiagnostic> listDiagnosticsOwned(String ownerId, String caseId) {
        requireOwnedCase(ownerId, caseId);
        return jdbc.query("""
                        SELECT d.diagnostic_id, d.case_id, d.subject_type, d.basis_id, d.source_id,
                               d.material_id, d.cannot_confirm_reason, d.failure_stage, d.locator_json,
                               d.evidence_digest, d.created_at
                        FROM review_m0_input_diagnostic d
                        JOIN review_m0_case c ON c.case_id=d.case_id
                        WHERE d.case_id=? AND c.user_id=?
                        ORDER BY d.created_at, d.diagnostic_id
                        """, this::mapDiagnostic, caseId, ownerId);
    }

    @Override
    public List<MaterialAssignment> listAssignmentsOwned(
            String ownerId,
            String caseId,
            long assignmentRevision
    ) {
        requireOwnedCase(ownerId, caseId);
        return jdbc.query("""
                        SELECT a.assignment_id, a.case_id, a.material_id, a.requirement_id,
                               a.status, a.assignment_revision, a.decided_by, a.created_at
                        FROM review_m0_material_assignment a
                        JOIN review_m0_case c ON c.case_id=a.case_id
                        WHERE a.case_id=? AND c.user_id=? AND a.assignment_revision=?
                        ORDER BY a.material_id, a.requirement_id
                        """, this::mapAssignment, caseId, ownerId, assignmentRevision);
    }

    @Override
    public Optional<SubmissionSource> findSourceOwned(String ownerId, String caseId, String sourceId) {
        requireOwnedCase(ownerId, caseId);
        return jdbc.query("SELECT " + SOURCE_COLUMNS + " FROM review_m0_submission_source s "
                        + "JOIN review_m0_case c ON c.case_id=s.case_id "
                        + "WHERE s.source_id=? AND s.case_id=? AND c.user_id=?",
                this::mapSource, sourceId, caseId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<SubmissionMaterial> findMaterialOwned(String ownerId, String caseId, String materialId) {
        requireOwnedCase(ownerId, caseId);
        return jdbc.query("SELECT " + MATERIAL_COLUMNS + " FROM review_m0_material m "
                        + "JOIN review_m0_case c ON c.case_id=m.case_id "
                        + "WHERE m.material_id=? AND m.case_id=? AND c.user_id=?",
                this::mapMaterial, materialId, caseId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<UploadConfirmation> findCurrentUploadConfirmationOwned(
            String ownerId,
            String caseId,
            String checklistSnapshotId,
            long materialSetRevision
    ) {
        requireOwnedCase(ownerId, caseId);
        return jdbc.query("""
                        SELECT u.upload_confirmation_id, u.case_id, u.checklist_snapshot_id,
                               u.material_set_revision, u.material_set_digest,
                               u.confirmed_by, u.confirmed_at
                        FROM review_m0_upload_confirmation u
                        JOIN review_m0_case c ON c.case_id=u.case_id
                        WHERE u.case_id=? AND c.user_id=? AND u.checklist_snapshot_id=?
                          AND u.material_set_revision=?
                        ORDER BY u.confirmed_at DESC, u.upload_confirmation_id DESC
                        LIMIT 1
                        """, this::mapUploadConfirmation,
                caseId, ownerId, checklistSnapshotId, materialSetRevision).stream().findFirst();
    }

    @Override
    public SubmissionSource insertSourceOwned(String ownerId, SubmissionSource source) {
        try {
            int inserted = jdbc.update("""
                            INSERT INTO review_m0_submission_source(
                                source_id, case_id, file_id, original_file_name_snapshot,
                                declared_content_type, formal_file_type, status, file_size_bytes,
                                file_digest, storage_version, attached_material_set_revision,
                                removed_material_set_revision, version, created_at, updated_at
                            )
                            SELECT ?, c.case_id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                            FROM review_m0_case c WHERE c.case_id=? AND c.user_id=?
                            """,
                    source.sourceId(), source.fileId(), source.originalFileNameSnapshot(),
                    source.declaredContentType(), source.formalFileType().name(), source.status().name(),
                    source.fileSizeBytes(), source.fileDigest(), source.storageVersion(),
                    source.attachedMaterialSetRevision(), source.removedMaterialSetRevision(), source.version(),
                    timestamp(source.createdAt()), timestamp(source.updatedAt()), source.caseId(), ownerId);
            if (inserted != 1) {
                throw MaterialErrors.caseNotFound();
            }
            return source;
        } catch (DataIntegrityViolationException error) {
            throw MaterialErrors.versionConflict("Submission source already exists");
        } catch (DataAccessException error) {
            throw MaterialErrors.storageUnavailable(error);
        }
    }

    @Override
    public SubmissionMaterial insertMaterialOwned(String ownerId, SubmissionMaterial material) {
        String zipEntryScopeDigest = material.kind() == MaterialKind.ZIP_ENTRY
                ? ReviewM0KeyDigests.zipEntryScope(material.sourceId(), material.normalizedEntryPath())
                : null;
        verifyZipEntryScopeDigest(material, zipEntryScopeDigest);
        try {
            int inserted = jdbc.update("""
                            INSERT INTO review_m0_material(
                                material_id, case_id, source_id, kind, normalized_entry_path, zip_entry_scope_digest,
                                original_entry_name, entry_index, content_digest, formal_file_type,
                                submission_file_name, normalized_submission_file_name, status,
                                cannot_confirm_reason, failure_stage, material_set_revision,
                                name_revision, version, created_at, updated_at
                            )
                            SELECT ?, s.case_id, s.source_id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                            FROM review_m0_submission_source s
                            JOIN review_m0_case c ON c.case_id=s.case_id
                            WHERE s.source_id=? AND s.case_id=? AND c.user_id=?
                            """,
                    material.materialId(), material.kind().name(), material.normalizedEntryPath(), zipEntryScopeDigest,
                    material.originalEntryName(), material.entryIndex(), material.contentDigest(),
                    material.formalFileType().name(), material.submissionFileName(),
                    material.normalizedSubmissionFileName(), material.status().name(),
                    name(material.cannotConfirmReason()), material.failureStage(),
                    material.materialSetRevision(), material.nameRevision(), material.version(),
                    timestamp(material.createdAt()), timestamp(material.updatedAt()),
                    material.sourceId(), material.caseId(), ownerId);
            if (inserted != 1) {
                throw MaterialErrors.resourceNotFound();
            }
            return material;
        } catch (DataIntegrityViolationException error) {
            verifyZipEntryScopeDigest(material, zipEntryScopeDigest);
            throw MaterialErrors.versionConflict("Submission material conflicts with an existing material");
        } catch (DataAccessException error) {
            throw MaterialErrors.storageUnavailable(error);
        }
    }

    private void verifyZipEntryScopeDigest(SubmissionMaterial material, String scopeDigest) {
        if (scopeDigest == null) {
            return;
        }
        List<ZipEntryScope> existing = jdbc.query("""
                        SELECT source_id, normalized_entry_path
                        FROM review_m0_material
                        WHERE zip_entry_scope_digest=?
                        """, (row, rowNumber) -> new ZipEntryScope(
                        row.getString("source_id"), row.getString("normalized_entry_path")), scopeDigest);
        if (existing.size() > 1 || existing.stream().anyMatch(value ->
                !value.sourceId().equals(material.sourceId())
                        || !value.normalizedEntryPath().equals(material.normalizedEntryPath()))) {
            throw MaterialErrors.storageUnavailable(
                    new IllegalStateException("ZIP entry scope digest collision failed closed"));
        }
    }

    @Override
    public InputDiagnostic insertDiagnosticOwned(String ownerId, InputDiagnostic diagnostic) {
        requireDiagnosticSubjectOwned(ownerId, diagnostic);
        try {
            int inserted = jdbc.update("""
                            INSERT INTO review_m0_input_diagnostic(
                                diagnostic_id, case_id, subject_type, basis_id, source_id, material_id,
                                cannot_confirm_reason, failure_stage, locator_json, evidence_digest, created_at
                            )
                            SELECT ?, c.case_id, ?, ?, ?, ?, ?, ?, ?, ?, ?
                            FROM review_m0_case c WHERE c.case_id=? AND c.user_id=?
                            """,
                    diagnostic.diagnosticId(), diagnostic.subjectType().name(), diagnostic.basisId(),
                    diagnostic.sourceId(), diagnostic.materialId(), diagnostic.cannotConfirmReason().name(),
                    diagnostic.failureStage(), diagnostic.locatorJson(), diagnostic.evidenceDigest(),
                    timestamp(diagnostic.createdAt()), diagnostic.caseId(), ownerId);
            if (inserted != 1) {
                throw MaterialErrors.caseNotFound();
            }
            return diagnostic;
        } catch (DataIntegrityViolationException error) {
            throw MaterialErrors.versionConflict("Input diagnostic conflicts with existing state");
        } catch (DataAccessException error) {
            throw MaterialErrors.storageUnavailable(error);
        }
    }

    @Override
    public SubmissionSource removeSourceOwned(
            String ownerId,
            String caseId,
            String sourceId,
            long expectedVersion,
            long removedMaterialSetRevision,
            Instant removedAt
    ) {
        int updated = jdbc.update("""
                        UPDATE review_m0_submission_source s
                        SET status='REMOVED', removed_material_set_revision=?, version=version+1, updated_at=?
                        WHERE s.source_id=? AND s.case_id=? AND s.status='ATTACHED' AND s.version=?
                          AND EXISTS (SELECT 1 FROM review_m0_case c
                                      WHERE c.case_id=s.case_id AND c.user_id=?)
                        """, removedMaterialSetRevision, timestamp(removedAt),
                sourceId, caseId, expectedVersion, ownerId);
        if (updated != 1) {
            SubmissionSource existing = findSourceOwned(ownerId, caseId, sourceId)
                    .orElseThrow(MaterialErrors::resourceNotFound);
            if (existing.status() == SubmissionSourceStatus.REMOVED) {
                return existing;
            }
            throw MaterialErrors.versionConflict("Submission source changed concurrently");
        }
        return findSourceOwned(ownerId, caseId, sourceId).orElseThrow(MaterialErrors::resourceNotFound);
    }

    @Override
    public void removeMaterialsForSourceOwned(
            String ownerId,
            String caseId,
            String sourceId,
            long removedMaterialSetRevision,
            Instant removedAt
    ) {
        jdbc.update("""
                        UPDATE review_m0_material m
                        SET status='REMOVED', cannot_confirm_reason=NULL, failure_stage=NULL,
                            material_set_revision=?, version=version+1, updated_at=?
                        WHERE m.source_id=? AND m.case_id=? AND m.status<>'REMOVED'
                          AND EXISTS (SELECT 1 FROM review_m0_case c
                                      WHERE c.case_id=m.case_id AND c.user_id=?)
                        """, removedMaterialSetRevision, timestamp(removedAt), sourceId, caseId, ownerId);
    }

    @Override
    public SubmissionMaterial renameMaterialOwned(
            String ownerId,
            String caseId,
            String materialId,
            long expectedVersion,
            String submissionFileName,
            String normalizedSubmissionFileName,
            long nameRevision,
            Instant renamedAt
    ) {
        int updated = jdbc.update("""
                        UPDATE review_m0_material m
                        SET submission_file_name=?, normalized_submission_file_name=?,
                            name_revision=?, version=version+1, updated_at=?
                        WHERE m.material_id=? AND m.case_id=? AND m.status<>'REMOVED' AND m.version=?
                          AND EXISTS (SELECT 1 FROM review_m0_case c
                                      WHERE c.case_id=m.case_id AND c.user_id=?)
                        """, submissionFileName, normalizedSubmissionFileName, nameRevision,
                timestamp(renamedAt), materialId, caseId, expectedVersion, ownerId);
        if (updated != 1) {
            if (findMaterialOwned(ownerId, caseId, materialId).isEmpty()) {
                throw MaterialErrors.resourceNotFound();
            }
            throw MaterialErrors.versionConflict("Submission material changed concurrently");
        }
        return findMaterialOwned(ownerId, caseId, materialId).orElseThrow(MaterialErrors::resourceNotFound);
    }

    @Override
    public boolean materialExistsOwned(String ownerId, String caseId, String materialId) {
        requireOwnedCase(ownerId, caseId);
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM review_m0_material m
                        JOIN review_m0_case c ON c.case_id=m.case_id
                        WHERE m.material_id=? AND m.case_id=? AND c.user_id=? AND m.status<>'REMOVED'
                        """, Integer.class, materialId, caseId, ownerId);
        return count != null && count == 1;
    }

    @Override
    public boolean requirementExistsOwned(String ownerId, String caseId, String requirementId) {
        requireOwnedCase(ownerId, caseId);
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM review_m0_requirement r
                        JOIN review_m0_case c ON c.case_id=r.case_id
                        WHERE r.requirement_id=? AND r.case_id=? AND c.user_id=?
                        """, Integer.class, requirementId, caseId, ownerId);
        return count != null && count == 1;
    }

    @Override
    public MaterialAssignment insertAssignmentOwned(String ownerId, MaterialAssignment assignment) {
        try {
            int inserted = jdbc.update("""
                            INSERT INTO review_m0_material_assignment(
                                assignment_id, case_id, material_id, requirement_id, status,
                                assignment_revision, decided_by, created_at
                            )
                            SELECT ?, c.case_id, m.material_id, r.requirement_id, ?, ?, ?, ?
                            FROM review_m0_case c
                            JOIN review_m0_material m ON m.case_id=c.case_id AND m.material_id=?
                            JOIN review_m0_requirement r ON r.case_id=c.case_id AND r.requirement_id=?
                            WHERE c.case_id=? AND c.user_id=? AND m.status<>'REMOVED'
                            """,
                    assignment.assignmentId(), assignment.status().name(), assignment.assignmentRevision(),
                    assignment.decidedBy(), timestamp(assignment.createdAt()), assignment.materialId(),
                    assignment.requirementId(), assignment.caseId(), ownerId);
            if (inserted != 1) {
                throw MaterialErrors.resourceNotFound();
            }
            return assignment;
        } catch (DataIntegrityViolationException error) {
            throw MaterialErrors.versionConflict("Material assignment conflicts with existing state");
        } catch (DataAccessException error) {
            throw MaterialErrors.storageUnavailable(error);
        }
    }

    @Override
    public UploadConfirmation insertUploadConfirmationOwned(
            String ownerId,
            UploadConfirmation confirmation
    ) {
        try {
            int inserted = jdbc.update("""
                            INSERT INTO review_m0_upload_confirmation(
                                upload_confirmation_id, case_id, checklist_snapshot_id,
                                material_set_revision, material_set_digest, confirmed_by, confirmed_at
                            )
                            SELECT ?, c.case_id, s.checklist_snapshot_id, ?, ?, ?, ?
                            FROM review_m0_case c
                            JOIN review_m0_checklist_snapshot s
                              ON s.case_id=c.case_id AND s.checklist_snapshot_id=?
                            WHERE c.case_id=? AND c.user_id=?
                            """,
                    confirmation.uploadConfirmationId(), confirmation.materialSetRevision(),
                    confirmation.materialSetDigest(), confirmation.confirmedBy(),
                    timestamp(confirmation.confirmedAt()), confirmation.checklistSnapshotId(),
                    confirmation.caseId(), ownerId);
            if (inserted != 1) {
                throw MaterialErrors.resourceNotFound();
            }
            return confirmation;
        } catch (DataIntegrityViolationException error) {
            return findCurrentUploadConfirmationOwned(
                    ownerId,
                    confirmation.caseId(),
                    confirmation.checklistSnapshotId(),
                    confirmation.materialSetRevision()).filter(existing ->
                            existing.materialSetDigest().equals(confirmation.materialSetDigest()))
                    .orElseThrow(() -> MaterialErrors.versionConflict(
                            "Upload confirmation conflicts with existing state"));
        } catch (DataAccessException error) {
            throw MaterialErrors.storageUnavailable(error);
        }
    }

    private void requireOwnedCase(String ownerId, String caseId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 128
                || caseId == null || caseId.isBlank() || caseId.length() > 64) {
            throw MaterialErrors.caseNotFound();
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_m0_case WHERE case_id=? AND user_id=?",
                Integer.class, caseId, ownerId);
        if (count == null || count != 1) {
            throw MaterialErrors.caseNotFound();
        }
    }

    private void requireDiagnosticSubjectOwned(String ownerId, InputDiagnostic diagnostic) {
        requireOwnedCase(ownerId, diagnostic.caseId());
        boolean exists = switch (diagnostic.subjectType()) {
            case SUBMISSION_SOURCE -> findSourceOwned(
                    ownerId, diagnostic.caseId(), diagnostic.sourceId()).isPresent();
            case MATERIAL -> findMaterialOwned(
                    ownerId, diagnostic.caseId(), diagnostic.materialId()).isPresent();
            case OFFICIAL_BASIS -> {
                Integer count = jdbc.queryForObject("""
                                SELECT COUNT(*) FROM review_m0_official_basis b
                                JOIN review_m0_case c ON c.case_id=b.case_id
                                WHERE b.basis_id=? AND b.case_id=? AND c.user_id=?
                                """, Integer.class, diagnostic.basisId(), diagnostic.caseId(), ownerId);
                yield count != null && count == 1;
            }
        };
        if (!exists) {
            throw MaterialErrors.resourceNotFound();
        }
    }

    private SubmissionSource mapSource(ResultSet row, int rowNumber) throws SQLException {
        return new SubmissionSource(
                row.getString("source_id"), row.getString("case_id"), row.getString("file_id"),
                row.getString("original_file_name_snapshot"), row.getString("declared_content_type"),
                FormalFileType.valueOf(row.getString("formal_file_type")),
                SubmissionSourceStatus.valueOf(row.getString("status")), row.getLong("file_size_bytes"),
                row.getString("file_digest"), row.getString("storage_version"),
                row.getLong("attached_material_set_revision"), nullableLong(row, "removed_material_set_revision"),
                row.getLong("version"), instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    private SubmissionMaterial mapMaterial(ResultSet row, int rowNumber) throws SQLException {
        return new SubmissionMaterial(
                row.getString("material_id"), row.getString("case_id"), row.getString("source_id"),
                MaterialKind.valueOf(row.getString("kind")), row.getString("normalized_entry_path"),
                row.getString("original_entry_name"), nullableInteger(row, "entry_index"),
                row.getString("content_digest"), FormalFileType.valueOf(row.getString("formal_file_type")),
                row.getString("submission_file_name"), row.getString("normalized_submission_file_name"),
                MaterialStatus.valueOf(row.getString("status")),
                enumValue(CannotConfirmReason.class, row.getString("cannot_confirm_reason")),
                row.getString("failure_stage"), row.getLong("material_set_revision"),
                row.getLong("name_revision"), row.getLong("version"),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    private InputDiagnostic mapDiagnostic(ResultSet row, int rowNumber) throws SQLException {
        return new InputDiagnostic(
                row.getString("diagnostic_id"), row.getString("case_id"),
                DiagnosticSubjectType.valueOf(row.getString("subject_type")),
                row.getString("basis_id"), row.getString("source_id"), row.getString("material_id"),
                CannotConfirmReason.valueOf(row.getString("cannot_confirm_reason")),
                row.getString("failure_stage"), row.getString("locator_json"),
                row.getString("evidence_digest"), instant(row.getTimestamp("created_at")));
    }

    private MaterialAssignment mapAssignment(ResultSet row, int rowNumber) throws SQLException {
        return new MaterialAssignment(
                row.getString("assignment_id"), row.getString("case_id"), row.getString("material_id"),
                row.getString("requirement_id"), AssignmentStatus.valueOf(row.getString("status")),
                row.getLong("assignment_revision"), row.getString("decided_by"),
                instant(row.getTimestamp("created_at")));
    }

    private UploadConfirmation mapUploadConfirmation(ResultSet row, int rowNumber) throws SQLException {
        return new UploadConfirmation(
                row.getString("upload_confirmation_id"), row.getString("case_id"),
                row.getString("checklist_snapshot_id"), row.getLong("material_set_revision"),
                row.getString("material_set_digest"), row.getString("confirmed_by"),
                instant(row.getTimestamp("confirmed_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private record ZipEntryScope(String sourceId, String normalizedEntryPath) {
    }
}
