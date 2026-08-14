package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.RemoteCall;
import com.javaee.aiservice.review.m0.model.RemoteCallState;
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
public class JdbcReviewM0RemoteCallStore implements ReviewM0RemoteCallStore {
    private static final String COLUMNS = """
            remote_call_id, job_id, consent_id, request_digest, state,
            provider_request_id, response_digest, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final RowMapper<RemoteCall> mapper = this::map;

    public JdbcReviewM0RemoteCallStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public RemoteCall beginSendIntent(RemoteCall intent, String workerId, Instant now) {
        if (intent.state() != RemoteCallState.SEND_INTENT || intent.responseDigest() != null) {
            throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT, "Remote call must begin at SEND_INTENT");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            requireValidJobLease(intent.jobId(), workerId, now);
            List<ConsentRow> consents = jdbcTemplate.query("""
                            SELECT consumed_by_job_id, payload_digest
                            FROM review_m0_remote_consent WHERE consent_id = ? FOR UPDATE
                            """,
                    (resultSet, rowNumber) -> new ConsentRow(
                            resultSet.getString("consumed_by_job_id"), resultSet.getString("payload_digest")),
                    intent.consentId());
            if (consents.isEmpty()) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED, "Remote consent was not found");
            }
            ConsentRow consent = consents.get(0);
            if (!consent.payloadDigest().equals(intent.requestDigest())) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE,
                        "Remote request digest does not match confirmed preview");
            }
            if (consent.consumedByJobId() != null && !consent.consumedByJobId().equals(intent.jobId())) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_CONSUMED,
                        "Remote consent was consumed by another logical job");
            }
            if (consent.consumedByJobId() == null) {
                int consumed = jdbcTemplate.update("""
                                UPDATE review_m0_remote_consent
                                SET consumed_by_job_id = ?, consumed_at = ?
                                WHERE consent_id = ? AND consumed_by_job_id IS NULL
                                """,
                        intent.jobId(), timestamp(now), intent.consentId());
                if (consumed != 1) {
                    throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_CONSUMED,
                            "Remote consent changed concurrently");
                }
            }
            try {
                jdbcTemplate.update("""
                                INSERT INTO review_m0_remote_call(
                                    remote_call_id, job_id, consent_id, request_digest, state,
                                    provider_request_id, response_digest, created_at, updated_at
                                ) VALUES (?, ?, ?, ?, 'SEND_INTENT', NULL, NULL, ?, ?)
                                """,
                        intent.remoteCallId(), intent.jobId(), intent.consentId(), intent.requestDigest(),
                        timestamp(now), timestamp(now));
                return findByJob(intent.jobId()).orElseThrow();
            } catch (DuplicateKeyException duplicate) {
                RemoteCall existing = findByJob(intent.jobId()).orElseThrow();
                if (!existing.consentId().equals(intent.consentId())
                        || !existing.requestDigest().equals(intent.requestDigest())) {
                    throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                            "Logical job already has a different remote call");
                }
                return existing;
            }
        }));
    }

    @Override
    public RemoteCall recordResponse(
            String jobId,
            String workerId,
            String providerRequestId,
            String responseDigest,
            Instant now
    ) {
        requireDigest(responseDigest);
        return Objects.requireNonNull(transactions.execute(status -> {
            requireValidJobLease(jobId, workerId, now);
            RemoteCall current = requireCallLocked(jobId);
            if (current.state() == RemoteCallState.RESPONSE_RECORDED) {
                if (Objects.equals(current.providerRequestId(), providerRequestId)
                        && current.responseDigest().equals(responseDigest)) {
                    return current;
                }
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                        "Recorded remote response cannot be replaced");
            }
            if (current.state() != RemoteCallState.SEND_INTENT) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_OUTCOME_UNKNOWN,
                        "Outcome-unknown calls cannot accept a response without provider reconciliation");
            }
            jdbcTemplate.update("""
                            UPDATE review_m0_remote_call
                            SET state = 'RESPONSE_RECORDED', provider_request_id = ?, response_digest = ?, updated_at = ?
                            WHERE job_id = ? AND state = 'SEND_INTENT'
                            """,
                    providerRequestId, responseDigest, timestamp(now), jobId);
            return findByJob(jobId).orElseThrow();
        }));
    }

    @Override
    public RemoteCall markOutcomeUnknown(String jobId, String workerId, Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            requireValidJobLease(jobId, workerId, now);
            RemoteCall current = requireCallLocked(jobId);
            if (current.state() == RemoteCallState.OUTCOME_UNKNOWN) {
                return current;
            }
            if (current.state() == RemoteCallState.RESPONSE_RECORDED) {
                return current;
            }
            jdbcTemplate.update("""
                            UPDATE review_m0_remote_call
                            SET state = 'OUTCOME_UNKNOWN', updated_at = ?
                            WHERE job_id = ? AND state = 'SEND_INTENT'
                            """,
                    timestamp(now), jobId);
            return findByJob(jobId).orElseThrow();
        }));
    }

    @Override
    public Optional<RemoteCall> findByJob(String jobId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM review_m0_remote_call WHERE job_id = ?",
                mapper, jobId).stream().findFirst();
    }

    private void requireValidJobLease(String jobId, String workerId, Instant now) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM review_m0_job
                        WHERE job_id = ? AND job_type = 'CHECKLIST_EXTRACTION' AND status = 'RUNNING'
                          AND lease_owner = ? AND lease_expires_at > ?
                        """,
                Integer.class, jobId, workerId, timestamp(now));
        if (count == null || count != 1) {
            throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                    "Worker does not hold the checklist-extraction lease");
        }
    }

    private RemoteCall requireCallLocked(String jobId) {
        List<RemoteCall> calls = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM review_m0_remote_call WHERE job_id = ? FOR UPDATE",
                mapper, jobId);
        if (calls.isEmpty()) {
            throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT, "Remote SEND_INTENT does not exist");
        }
        return calls.get(0);
    }

    private RemoteCall map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RemoteCall(
                resultSet.getString("remote_call_id"), resultSet.getString("job_id"),
                resultSet.getString("consent_id"), resultSet.getString("request_digest"),
                RemoteCallState.valueOf(resultSet.getString("state")),
                resultSet.getString("provider_request_id"), resultSet.getString("response_digest"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
    }

    private static void requireDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT, "Remote response digest is invalid");
        }
    }

    private static ReviewM0Exception conflict(ReviewM0ErrorCode code, String message) {
        return new ReviewM0Exception(code, message);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record ConsentRow(String consumedByJobId, String payloadDigest) {
    }
}
