package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.model.ChecklistLifecycle;
import com.javaee.aiservice.review.m0.model.ChecklistSnapshot;
import com.javaee.aiservice.review.m0.model.CanonicalJsonIntegrity;
import com.javaee.aiservice.review.m0.model.ProcessingMode;
import com.javaee.aiservice.review.m0.model.ReviewCase;
import com.javaee.aiservice.review.m0.model.RevisionKind;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcReviewCaseStore implements ReviewCaseStore {
    private static final String CASE_COLUMNS = """
            case_id, user_id, title, processing_mode, basis_revision, checklist_revision,
            material_set_revision, assignment_revision, name_revision, version,
            current_checklist_snapshot_id, current_report_id, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final RowMapper<ReviewCase> caseMapper = this::mapCase;

    public JdbcReviewCaseStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public ReviewCase create(ReviewCase reviewCase) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO review_m0_case(
                                case_id, user_id, title, processing_mode, basis_revision, checklist_revision,
                                material_set_revision, assignment_revision, name_revision, version,
                                current_checklist_snapshot_id, current_report_id, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    reviewCase.caseId(), reviewCase.userId(), reviewCase.title(), reviewCase.processingMode().name(),
                    reviewCase.basisRevision(), reviewCase.checklistRevision(), reviewCase.materialSetRevision(),
                    reviewCase.assignmentRevision(), reviewCase.nameRevision(), reviewCase.version(),
                    reviewCase.currentChecklistSnapshotId(), reviewCase.currentReportId(),
                    timestamp(reviewCase.createdAt()), timestamp(reviewCase.updatedAt()));
            return reviewCase;
        } catch (DataIntegrityViolationException error) {
            throw StoreErrors.versionConflict("Review case already exists");
        }
    }

    @Override
    public Optional<ReviewCase> findOwned(String ownerId, String caseId) {
        requireOwner(ownerId);
        return jdbcTemplate.query(
                "SELECT " + CASE_COLUMNS + " FROM review_m0_case WHERE case_id = ? AND user_id = ?",
                caseMapper, caseId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<ChecklistSnapshot> findChecklistSnapshotOwned(
            String ownerId,
            String caseId,
            String checklistSnapshotId
    ) {
        requireOwner(ownerId);
        return jdbcTemplate.query("""
                        SELECT s.checklist_snapshot_id, s.case_id, s.checklist_revision_id,
                               s.basis_revision, s.checklist_revision, s.snapshot_json, s.snapshot_digest,
                               s.lifecycle, s.confirmed_by, s.confirmed_at, s.superseded_at
                        FROM review_m0_checklist_snapshot s
                        JOIN review_m0_case c ON c.case_id = s.case_id
                        WHERE s.checklist_snapshot_id = ? AND s.case_id = ? AND c.user_id = ?
                        """,
                (resultSet, rowNumber) -> mapSnapshot(resultSet),
                checklistSnapshotId, caseId, ownerId).stream().findFirst();
    }

    @Override
    public ReviewCase advanceRevisionOwned(
            String ownerId,
            String caseId,
            long expectedVersion,
            RevisionKind revisionKind,
            String outdatedReason
    ) {
        requireOwner(ownerId);
        Objects.requireNonNull(revisionKind, "revisionKind");
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase current = requireOwnedLocked(ownerId, caseId);
            requireVersion(current, expectedVersion);
            Instant now = Instant.now();
            if (revisionKind == RevisionKind.BASIS && current.currentChecklistSnapshotId() != null) {
                jdbcTemplate.update("""
                                UPDATE review_m0_checklist_snapshot
                                SET lifecycle = 'STALE_BASIS'
                                WHERE checklist_snapshot_id = ? AND case_id = ? AND lifecycle = 'CONFIRMED'
                                """,
                        current.currentChecklistSnapshotId(), caseId);
                jdbcTemplate.update("""
                                UPDATE review_m0_checklist_revision
                                SET lifecycle = 'STALE_BASIS'
                                WHERE checklist_revision_id = (
                                    SELECT checklist_revision_id FROM review_m0_checklist_snapshot
                                    WHERE checklist_snapshot_id = ?
                                ) AND lifecycle = 'CONFIRMED'
                                """,
                        current.currentChecklistSnapshotId());
            }
            expireCurrentReport(current, outdatedReason, now);
            String revisionAssignment = switch (revisionKind) {
                case BASIS -> "basis_revision = basis_revision + 1,";
                case MATERIAL_SET -> "material_set_revision = material_set_revision + 1,";
                case ASSIGNMENT -> "assignment_revision = assignment_revision + 1,";
                case NAME -> "name_revision = name_revision + 1,";
                case ENGINE_VERSION -> "";
            };
            int updated = jdbcTemplate.update("UPDATE review_m0_case SET " + revisionAssignment + " "
                            + "current_report_id = NULL, version = version + 1, updated_at = ? "
                            + "WHERE case_id = ? AND user_id = ? AND version = ?",
                    timestamp(now), caseId, ownerId, expectedVersion);
            if (updated != 1) {
                throw StoreErrors.versionConflict("Review case changed concurrently");
            }
            return requireOwned(ownerId, caseId);
        }));
    }

    @Override
    public ChecklistSnapshot publishChecklistSnapshotOwned(
            String ownerId,
            String caseId,
            long expectedVersion,
            ChecklistSnapshot snapshot,
            String outdatedReason
    ) {
        requireOwner(ownerId);
        if (!snapshot.caseId().equals(caseId) || snapshot.lifecycle() != ChecklistLifecycle.CONFIRMED) {
            throw StoreErrors.versionConflict("Checklist snapshot does not describe the owned case and CONFIRMED state");
        }
        CanonicalJsonIntegrity.requireValid(snapshot.snapshotJson(), snapshot.snapshotDigest(), "Checklist snapshot");
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewCase current = requireOwnedLocked(ownerId, caseId);
            requireVersion(current, expectedVersion);
            if (snapshot.basisRevision() != current.basisRevision()
                    || snapshot.checklistRevision() <= current.checklistRevision()) {
                throw StoreErrors.versionConflict("Checklist snapshot is based on stale revisions");
            }
            Instant now = snapshot.confirmedAt();
            if (current.currentChecklistSnapshotId() != null) {
                jdbcTemplate.update("""
                                UPDATE review_m0_checklist_snapshot
                                SET lifecycle = 'SUPERSEDED', superseded_at = ?
                                WHERE checklist_snapshot_id = ? AND case_id = ? AND lifecycle = 'CONFIRMED'
                                """,
                        timestamp(now), current.currentChecklistSnapshotId(), caseId);
                jdbcTemplate.update("""
                                UPDATE review_m0_checklist_revision
                                SET lifecycle = 'SUPERSEDED'
                                WHERE checklist_revision_id = (
                                    SELECT checklist_revision_id FROM review_m0_checklist_snapshot
                                    WHERE checklist_snapshot_id = ?
                                ) AND lifecycle = 'CONFIRMED'
                                """,
                        current.currentChecklistSnapshotId());
            }
            int revisionUpdated = jdbcTemplate.update("""
                            UPDATE review_m0_checklist_revision SET lifecycle = 'CONFIRMED'
                            WHERE checklist_revision_id = ? AND case_id = ? AND lifecycle = 'CANDIDATE_DRAFT'
                              AND basis_revision = ? AND revision_number = ?
                            """,
                    snapshot.checklistRevisionId(), caseId, snapshot.basisRevision(), snapshot.checklistRevision());
            if (revisionUpdated != 1) {
                throw StoreErrors.versionConflict("Checklist draft revision cannot be published");
            }
            jdbcTemplate.update("""
                            INSERT INTO review_m0_checklist_snapshot(
                                checklist_snapshot_id, case_id, checklist_revision_id, basis_revision,
                                checklist_revision, snapshot_json, snapshot_digest, lifecycle,
                                confirmed_by, confirmed_at, superseded_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    snapshot.checklistSnapshotId(), caseId, snapshot.checklistRevisionId(),
                    snapshot.basisRevision(), snapshot.checklistRevision(), snapshot.snapshotJson(),
                    snapshot.snapshotDigest(), snapshot.lifecycle().name(), snapshot.confirmedBy(),
                    timestamp(snapshot.confirmedAt()), timestamp(snapshot.supersededAt()));
            expireCurrentReport(current, outdatedReason, now);
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_case
                            SET checklist_revision = ?, current_checklist_snapshot_id = ?, current_report_id = NULL,
                                version = version + 1, updated_at = ?
                            WHERE case_id = ? AND user_id = ? AND version = ?
                            """,
                    snapshot.checklistRevision(), snapshot.checklistSnapshotId(), timestamp(now),
                    caseId, ownerId, expectedVersion);
            if (updated != 1) {
                throw StoreErrors.versionConflict("Review case changed concurrently");
            }
            return snapshot;
        }));
    }

    private void expireCurrentReport(ReviewCase current, String reason, Instant now) {
        if (current.currentReportId() == null) {
            return;
        }
        int updated = jdbcTemplate.update("""
                        UPDATE review_m0_report
                        SET freshness = 'OUTDATED', outdated_at = ?, outdated_reason = ?
                        WHERE report_id = ? AND case_id = ? AND freshness = 'CURRENT'
                        """,
                timestamp(now), reason, current.currentReportId(), current.caseId());
        if (updated != 1) {
            throw StoreErrors.versionConflict("Current report cannot be expired atomically");
        }
    }

    private ReviewCase requireOwnedLocked(String ownerId, String caseId) {
        List<ReviewCase> rows = jdbcTemplate.query(
                "SELECT " + CASE_COLUMNS + " FROM review_m0_case "
                        + "WHERE case_id = ? AND user_id = ? FOR UPDATE",
                caseMapper, caseId, ownerId);
        if (rows.isEmpty()) {
            throw StoreErrors.caseNotFound();
        }
        return rows.get(0);
    }

    private ReviewCase requireOwned(String ownerId, String caseId) {
        return findOwned(ownerId, caseId).orElseThrow(StoreErrors::caseNotFound);
    }

    private static void requireVersion(ReviewCase current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw StoreErrors.versionConflict("Review case version changed concurrently");
        }
    }

    private ReviewCase mapCase(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ReviewCase(
                resultSet.getString("case_id"), resultSet.getString("user_id"), resultSet.getString("title"),
                ProcessingMode.valueOf(resultSet.getString("processing_mode")),
                resultSet.getLong("basis_revision"), resultSet.getLong("checklist_revision"),
                resultSet.getLong("material_set_revision"), resultSet.getLong("assignment_revision"),
                resultSet.getLong("name_revision"), resultSet.getLong("version"),
                resultSet.getString("current_checklist_snapshot_id"), resultSet.getString("current_report_id"),
                instant(resultSet.getTimestamp("created_at")), instant(resultSet.getTimestamp("updated_at")));
    }

    private ChecklistSnapshot mapSnapshot(ResultSet resultSet) throws SQLException {
        String json = resultSet.getString("snapshot_json");
        String digest = resultSet.getString("snapshot_digest");
        CanonicalJsonIntegrity.requireValid(json, digest, "Stored checklist snapshot");
        return new ChecklistSnapshot(
                resultSet.getString("checklist_snapshot_id"), resultSet.getString("case_id"),
                resultSet.getString("checklist_revision_id"), resultSet.getLong("basis_revision"),
                resultSet.getLong("checklist_revision"), json, digest,
                ChecklistLifecycle.valueOf(resultSet.getString("lifecycle")),
                resultSet.getString("confirmed_by"), instant(resultSet.getTimestamp("confirmed_at")),
                instant(resultSet.getTimestamp("superseded_at")));
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    static void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 128) {
            throw StoreErrors.caseNotFound();
        }
    }
}
