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
            LeasedJob job = requireValidJobLease(intent.jobId(), workerId, now);
            Optional<RemoteCall> existingCall = findByJobInternal(intent.jobId());
            List<ConsentRow> consents = jdbcTemplate.query("""
                            SELECT consent.case_id AS consent_case_id,
                                   consent.operation AS consent_operation,
                                   consent.basis_revision AS consent_basis_revision,
                                   consent.payload_digest AS consent_payload_digest,
                                   consent.confirmed_by,
                                   consent.consumed_by_job_id,
                                   preview.case_id AS preview_case_id,
                                   preview.operation AS preview_operation,
                                   preview.basis_revision AS preview_basis_revision,
                                   preview.payload_digest AS preview_payload_digest,
                                   preview.status AS preview_status,
                                   preview.created_by AS preview_created_by
                            FROM review_m0_remote_consent consent
                            JOIN review_m0_remote_preview preview ON preview.preview_id = consent.preview_id
                            WHERE consent.consent_id = ?
                            FOR UPDATE
                            """,
                    (resultSet, rowNumber) -> new ConsentRow(
                            resultSet.getString("consent_case_id"),
                            resultSet.getString("consent_operation"),
                            resultSet.getLong("consent_basis_revision"),
                            resultSet.getString("consent_payload_digest"),
                            resultSet.getString("confirmed_by"),
                            resultSet.getString("consumed_by_job_id"),
                            resultSet.getString("preview_case_id"),
                            resultSet.getString("preview_operation"),
                            resultSet.getLong("preview_basis_revision"),
                            resultSet.getString("preview_payload_digest"),
                            resultSet.getString("preview_status"),
                            resultSet.getString("preview_created_by")),
                    intent.consentId());
            if (consents.isEmpty()) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED, "Remote consent was not found");
            }
            ConsentRow consent = consents.get(0);
            if (!consent.caseId().equals(job.caseId()) || !consent.confirmedBy().equals(job.ownerId())
                    || !consent.previewCaseId().equals(job.caseId())
                    || !consent.previewCreatedBy().equals(job.ownerId())) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                        "Remote consent was not found");
            }
            if (!consent.operation().equals(job.jobType())
                    || !consent.previewOperation().equals(job.jobType())) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_REQUIRED,
                        "Remote consent operation does not match the logical job");
            }
            if (consent.basisRevision() != job.currentBasisRevision()
                    || consent.previewBasisRevision() != job.currentBasisRevision()
                    || !"CONFIRMED".equals(consent.previewStatus())) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE,
                        "Remote consent does not match the current Case revision");
            }
            if (!consent.payloadDigest().equals(intent.requestDigest())
                    || !consent.previewPayloadDigest().equals(intent.requestDigest())) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_PREVIEW_STALE,
                        "Remote request digest does not match confirmed preview");
            }
            if (existingCall.isPresent()) {
                RemoteCall existing = existingCall.get();
                if (!existing.consentId().equals(intent.consentId())
                        || !existing.requestDigest().equals(intent.requestDigest())
                        || !intent.jobId().equals(consent.consumedByJobId())) {
                    throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                            "Logical job already has an unbound remote call");
                }
                return existing;
            }
            if (consent.consumedByJobId() != null) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_CONSUMED,
                        "Remote consent was already consumed");
            }
            int consumed = jdbcTemplate.update("""
                            UPDATE review_m0_remote_consent
                            SET consumed_by_job_id = ?, consumed_at = ?
                            WHERE consent_id = ?
                              AND case_id = ?
                              AND operation = ?
                              AND basis_revision = ?
                              AND payload_digest = ?
                              AND confirmed_by = ?
                              AND consumed_by_job_id IS NULL
                            """,
                    intent.jobId(), timestamp(now), intent.consentId(), job.caseId(), job.jobType(),
                    job.currentBasisRevision(), intent.requestDigest(), job.ownerId());
            if (consumed != 1) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_REMOTE_CONSENT_CONSUMED,
                        "Remote consent changed concurrently");
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
                return findByJobInternal(intent.jobId()).orElseThrow();
            } catch (DuplicateKeyException duplicate) {
                throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                        "Logical job already has a remote call");
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
            return findByJobInternal(jobId).orElseThrow();
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
            return findByJobInternal(jobId).orElseThrow();
        }));
    }

    @Override
    public Optional<RemoteCall> findOwned(String ownerId, String caseId, String jobId) {
        if (ownerId == null || ownerId.isBlank() || caseId == null || caseId.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT remote_call.remote_call_id, remote_call.job_id, remote_call.consent_id,
                               remote_call.request_digest, remote_call.state,
                               remote_call.provider_request_id, remote_call.response_digest,
                               remote_call.created_at, remote_call.updated_at
                        FROM review_m0_remote_call remote_call
                        JOIN review_m0_job job ON job.job_id = remote_call.job_id
                        JOIN review_m0_case review_case
                          ON review_case.case_id = job.case_id AND review_case.user_id = job.user_id
                        JOIN review_m0_remote_consent consent
                          ON consent.consent_id = remote_call.consent_id
                         AND consent.case_id = job.case_id
                         AND consent.operation = job.job_type
                         AND consent.confirmed_by = job.user_id
                         AND consent.consumed_by_job_id = job.job_id
                         AND consent.payload_digest = remote_call.request_digest
                        JOIN review_m0_remote_preview preview
                          ON preview.preview_id = consent.preview_id
                         AND preview.case_id = consent.case_id
                         AND preview.operation = consent.operation
                         AND preview.basis_revision = consent.basis_revision
                         AND preview.payload_digest = consent.payload_digest
                         AND preview.created_by = consent.confirmed_by
                         AND preview.status IN ('CONFIRMED', 'CONSUMED')
                        WHERE job.job_id = ? AND job.case_id = ? AND job.user_id = ?
                        """,
                mapper, jobId, caseId, ownerId).stream().findFirst();
    }

    private Optional<RemoteCall> findByJobInternal(String jobId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM review_m0_remote_call WHERE job_id = ?",
                mapper, jobId).stream().findFirst();
    }

    private LeasedJob requireValidJobLease(String jobId, String workerId, Instant now) {
        List<LeasedJob> jobs = jdbcTemplate.query("""
                        SELECT job.case_id, job.user_id, job.job_type, review_case.basis_revision
                        FROM review_m0_job job
                        JOIN review_m0_case review_case
                          ON review_case.case_id = job.case_id AND review_case.user_id = job.user_id
                        WHERE job.job_id = ? AND job.job_type = 'CHECKLIST_EXTRACTION'
                          AND job.status = 'RUNNING' AND job.lease_owner = ? AND job.lease_expires_at > ?
                        FOR UPDATE
                        """,
                (resultSet, rowNumber) -> new LeasedJob(
                        resultSet.getString("case_id"), resultSet.getString("user_id"),
                        resultSet.getString("job_type"), resultSet.getLong("basis_revision")),
                jobId, workerId, timestamp(now));
        if (jobs.size() != 1) {
            throw conflict(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT,
                    "Worker does not hold the checklist-extraction lease");
        }
        return jobs.get(0);
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

    private record LeasedJob(String caseId, String ownerId, String jobType, long currentBasisRevision) {
    }

    private record ConsentRow(
            String caseId,
            String operation,
            long basisRevision,
            String payloadDigest,
            String confirmedBy,
            String consumedByJobId,
            String previewCaseId,
            String previewOperation,
            long previewBasisRevision,
            String previewPayloadDigest,
            String previewStatus,
            String previewCreatedBy
    ) {
    }
}
