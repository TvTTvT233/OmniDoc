package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.model.IdempotencyState;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.springframework.dao.DuplicateKeyException;
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
public class JdbcReviewM0IdempotencyStore implements ReviewM0IdempotencyStore {
    private static final String COLUMNS = """
            owner_id, http_method, canonical_path, idempotency_key, request_digest,
            response_status, response_type, response_id, state, created_at, updated_at
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
                                    owner_id, http_method, canonical_path, idempotency_key, request_digest,
                                    response_status, response_type, response_id, state, created_at, updated_at
                                ) VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, 'IN_PROGRESS', ?, ?)
                                """,
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
                return existing;
            }
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_idempotency
                            SET response_status = ?, response_type = ?, response_id = ?,
                                state = 'COMPLETED', updated_at = ?
                            WHERE owner_id = ? AND http_method = ? AND canonical_path = ?
                              AND idempotency_key = ? AND request_digest = ? AND state = 'IN_PROGRESS'
                            """,
                    responseStatus, responseType, responseId, timestamp(Instant.now()),
                    scope.ownerId(), scope.httpMethod(), scope.canonicalPath(), scope.idempotencyKey(), requestDigest);
            if (updated != 1) {
                throw conflict("Idempotency result changed concurrently");
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
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM review_m0_idempotency "
                        + "WHERE owner_id = ? AND http_method = ? AND canonical_path = ? AND idempotency_key = ?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                mapper, scope.ownerId(), scope.httpMethod(), scope.canonicalPath(), scope.idempotencyKey());
    }

    private IdempotencyRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        Integer status = resultSet.getObject("response_status", Integer.class);
        return new IdempotencyRecord(
                new IdempotencyScope(
                        resultSet.getString("owner_id"), resultSet.getString("http_method"),
                        resultSet.getString("canonical_path"), resultSet.getString("idempotency_key")),
                resultSet.getString("request_digest"), status, resultSet.getString("response_type"),
                resultSet.getString("response_id"), IdempotencyState.valueOf(resultSet.getString("state")),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
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
