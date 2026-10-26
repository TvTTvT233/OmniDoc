package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.model.IdempotencyState;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcReviewM0IdempotencyStore implements ReviewM0IdempotencyStore {
    private static final String COLUMNS = """
            idem.scope_digest, idem.owner_id, idem.http_method, idem.canonical_path, idem.idempotency_key,
            idem.request_digest, idem.response_status, idem.response_type, idem.response_id,
            idem.state, idem.created_at, idem.updated_at,
            snapshot.scope_digest AS snapshot_scope_digest,
            snapshot.owner_id AS snapshot_owner_id,
            snapshot.http_method AS snapshot_http_method,
            snapshot.canonical_path AS snapshot_canonical_path,
            snapshot.idempotency_key AS snapshot_idempotency_key,
            snapshot.format_version AS snapshot_format_version,
            snapshot.response_body AS snapshot_response_body,
            snapshot.response_body_bytes AS snapshot_response_body_bytes,
            snapshot.snapshot_digest AS snapshot_integrity_digest
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final RowMapper<IdempotencyRecord> mapper = this::map;

    public JdbcReviewM0IdempotencyStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public IdempotencyClaim claim(IdempotencyScope scope, String requestDigest) {
        requireDigest(requestDigest);
        return Objects.requireNonNull(transactions.execute(status -> {
            Instant now = Instant.now();
            try {
                jdbcTemplate.update("""
                                INSERT INTO review_m0_idempotency(
                                    scope_digest, owner_id, http_method, canonical_path, idempotency_key, request_digest,
                                    response_status, response_type, response_id, state, created_at, updated_at
                                ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 'IN_PROGRESS', ?, ?)
                                """,
                        IdempotencyResponseSnapshot.scopeDigest(scope),
                        scope.ownerId(), scope.httpMethod(), scope.canonicalPath(), scope.idempotencyKey(),
                        requestDigest, timestamp(now), timestamp(now));
                return new IdempotencyClaim(IdempotencyClaimStatus.STARTED,
                        require(scope, false));
            } catch (DuplicateKeyException duplicate) {
                IdempotencyRecord existing = require(scope, true);
                requireSameDigest(existing, requestDigest);
                return new IdempotencyClaim(
                        existing.state() == IdempotencyState.COMPLETED
                                ? IdempotencyClaimStatus.REPLAY_COMPLETED
                                : IdempotencyClaimStatus.REPLAY_IN_PROGRESS,
                        existing);
            }
        }));
    }

    @Override
    public IdempotencyRecord complete(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            String responseId
    ) {
        return completeInternal(
                scope, requestDigest, responseStatus, responseType, responseId, null, false);
    }

    @Override
    public IdempotencyRecord completeWithSnapshot(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            String responseId,
            IdempotencyResponseSnapshot responseSnapshot
    ) {
        if (responseSnapshot == null) {
            throw new IllegalArgumentException("Idempotency response snapshot is required");
        }
        responseSnapshot.requireValid(responseStatus, responseType, responseId);
        return completeInternal(
                scope, requestDigest, responseStatus, responseType, responseId,
                responseSnapshot, true);
    }

    @Override
    public IdempotencyRecord attachSnapshotToCompletedInCallerTransaction(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            String responseId,
            IdempotencyResponseSnapshot responseSnapshot
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "A caller transaction is required to attach an idempotency response snapshot");
        }
        requireDigest(requestDigest);
        if (responseSnapshot == null) {
            throw new IllegalArgumentException("Idempotency response snapshot is required");
        }
        responseSnapshot.requireValid(responseStatus, responseType, responseId);
        return Objects.requireNonNull(transactions.execute(status -> {
            IdempotencyRecord existing = require(scope, true);
            requireSameDigest(existing, requestDigest);
            if (existing.state() != IdempotencyState.COMPLETED
                    || existing.responseStatus() != responseStatus
                    || !responseType.equals(existing.responseType())
                    || !responseId.equals(existing.responseId())) {
                throw conflict("Completed idempotency response reference does not match");
            }
            if (existing.responseSnapshot() != null) {
                existing.responseSnapshot().requireValid(responseStatus, responseType, responseId);
                if (!existing.responseSnapshot().equals(responseSnapshot)) {
                    throw conflict("Completed idempotency response snapshot cannot be changed");
                }
                return existing;
            }
            insertSnapshot(scope, responseSnapshot);
            return require(scope, false);
        }));
    }

    private IdempotencyRecord completeInternal(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            String responseId,
            IdempotencyResponseSnapshot responseSnapshot,
            boolean snapshotRequired
    ) {
        requireDigest(requestDigest);
        if (responseStatus < 100 || responseStatus > 599
                || responseType == null || responseType.isBlank() || responseType.length() > 64
                || responseId == null || responseId.isBlank() || responseId.length() > 64) {
            throw new IllegalArgumentException("Idempotency response reference is invalid");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            IdempotencyRecord existing = require(scope, true);
            requireSameDigest(existing, requestDigest);
            if (existing.state() == IdempotencyState.COMPLETED) {
                if (existing.responseStatus() != responseStatus
                        || !existing.responseType().equals(responseType)
                        || !existing.responseId().equals(responseId)) {
                    throw conflict("Completed idempotency result cannot be changed");
                }
                if (snapshotRequired) {
                    IdempotencyResponseSnapshot persisted = existing.responseSnapshot();
                    if (persisted == null) {
                        throw IdempotencyResponseSnapshot.unavailable(
                                "Completed idempotency record has no response snapshot", null);
                    }
                    persisted.requireValid(responseStatus, responseType, responseId);
                    if (!persisted.equals(responseSnapshot)) {
                        throw conflict("Completed idempotency response snapshot cannot be changed");
                    }
                }
                return existing;
            }
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_idempotency
                            SET response_status = ?, response_type = ?, response_id = ?,
                                state = 'COMPLETED', updated_at = ?
                            WHERE scope_digest = ? AND owner_id = ? AND http_method = ? AND canonical_path = ?
                              AND idempotency_key = ? AND request_digest = ? AND state = 'IN_PROGRESS'
                            """,
                    responseStatus, responseType, responseId, timestamp(Instant.now()),
                    IdempotencyResponseSnapshot.scopeDigest(scope), scope.ownerId(), scope.httpMethod(),
                    scope.canonicalPath(), scope.idempotencyKey(), requestDigest);
            if (updated != 1) {
                throw conflict("Idempotency result changed concurrently");
            }
            if (snapshotRequired) {
                insertSnapshot(scope, responseSnapshot);
            }
            return require(scope, false);
        }));
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyScope scope) {
        return query(scope, false).stream().findFirst();
    }

    private IdempotencyRecord require(IdempotencyScope scope, boolean forUpdate) {
        List<IdempotencyRecord> rows = query(scope, forUpdate);
        if (rows.isEmpty()) {
            throw conflict("Idempotency claim does not exist");
        }
        return rows.get(0);
    }

    private List<IdempotencyRecord> query(IdempotencyScope scope, boolean forUpdate) {
        List<IdempotencyRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM review_m0_idempotency idem "
                        + "LEFT JOIN review_m0_idempotency_response_snapshot snapshot "
                        + "ON snapshot.scope_digest = ? "
                        + "WHERE idem.scope_digest = ?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                mapper, IdempotencyResponseSnapshot.scopeDigest(scope),
                IdempotencyResponseSnapshot.scopeDigest(scope));
        if (!rows.isEmpty() && !rows.get(0).scope().equals(scope)) {
            throw IdempotencyResponseSnapshot.unavailable(
                    "Idempotency scope digest collision failed closed", null);
        }
        return rows;
    }

    private IdempotencyRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        IdempotencyScope scope = new IdempotencyScope(
                resultSet.getString("owner_id"), resultSet.getString("http_method"),
                resultSet.getString("canonical_path"), resultSet.getString("idempotency_key"));
        if (!IdempotencyResponseSnapshot.scopeDigest(scope).equals(resultSet.getString("scope_digest"))) {
            throw IdempotencyResponseSnapshot.unavailable(
                    "Idempotency scope digest failed integrity validation", null);
        }
        Integer status = resultSet.getObject("response_status", Integer.class);
        IdempotencyResponseSnapshot snapshot = mapSnapshot(resultSet, scope);
        return new IdempotencyRecord(
                scope,
                resultSet.getString("request_digest"), status, resultSet.getString("response_type"),
                resultSet.getString("response_id"), snapshot,
                IdempotencyState.valueOf(resultSet.getString("state")),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
    }

    private IdempotencyResponseSnapshot mapSnapshot(ResultSet resultSet, IdempotencyScope scope)
            throws SQLException {
        String scopeDigest = resultSet.getString("snapshot_scope_digest");
        if (scopeDigest == null) {
            return null;
        }
        if (!scopeDigest.equals(IdempotencyResponseSnapshot.scopeDigest(scope))
                || !scope.ownerId().equals(resultSet.getString("snapshot_owner_id"))
                || !scope.httpMethod().equals(resultSet.getString("snapshot_http_method"))
                || !scope.canonicalPath().equals(resultSet.getString("snapshot_canonical_path"))
                || !scope.idempotencyKey().equals(resultSet.getString("snapshot_idempotency_key"))) {
            throw IdempotencyResponseSnapshot.unavailable(
                    "Idempotency response snapshot scope failed integrity validation", null);
        }
        return new IdempotencyResponseSnapshot(
                resultSet.getInt("snapshot_format_version"),
                resultSet.getString("snapshot_response_body"),
                resultSet.getLong("snapshot_response_body_bytes"),
                resultSet.getString("snapshot_integrity_digest"));
    }

    private void insertSnapshot(IdempotencyScope scope, IdempotencyResponseSnapshot snapshot) {
        jdbcTemplate.update("""
                        INSERT INTO review_m0_idempotency_response_snapshot(
                            scope_digest, owner_id, http_method, canonical_path, idempotency_key,
                            format_version, response_body, response_body_bytes, snapshot_digest, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                IdempotencyResponseSnapshot.scopeDigest(scope),
                scope.ownerId(), scope.httpMethod(), scope.canonicalPath(), scope.idempotencyKey(),
                snapshot.formatVersion(), snapshot.responseBody(), snapshot.responseBodyBytes(),
                snapshot.snapshotDigest(), timestamp(Instant.now()));
    }

    private static void requireSameDigest(IdempotencyRecord existing, String requestDigest) {
        if (!existing.requestDigest().equals(requestDigest)) {
            throw conflict("Idempotency key was reused with a different canonical payload");
        }
    }

    private static void requireDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestDigest must be a lower-case SHA-256 hex digest");
        }
    }

    private static ReviewM0Exception conflict(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT, message);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
