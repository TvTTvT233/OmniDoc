package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.CanonicalJsonIntegrity;
import com.javaee.aiservice.review.m0.model.ReviewJobCheckpoint;
import com.javaee.aiservice.review.m0.model.ReviewJobError;
import com.javaee.aiservice.review.m0.model.ReviewJobResultRef;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewJobType;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import com.javaee.aiservice.review.m0.model.ReviewM0StateMachines;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class JdbcReviewM0JobStore implements ReviewM0JobStore {
    private static final String JOB_COLUMNS = """
            job_id, case_id, user_id, job_type, status, request_json, request_digest,
            input_fingerprint, idempotency_key, progress_completed, progress_total, attempt,
            lease_owner, lease_expires_at, cancel_requested_at, result_type, result_id,
            error_code, error_retryable, error_message, version, created_at, started_at,
            heartbeat_at, finished_at
            """;
    private static final String CHECKPOINT_COLUMNS = """
            job_id, checkpoint_key, checkpoint_json, checkpoint_digest, version, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final RowMapper<ReviewJob> jobMapper = this::mapJob;
    private final RowMapper<ReviewJobCheckpoint> checkpointMapper = this::mapCheckpoint;

    public JdbcReviewM0JobStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public ReviewJob create(ReviewJob job) {
        if (job.status() != ReviewJobStatus.QUEUED || job.attempt() != 0
                || job.leaseOwner() != null || job.startedAt() != null || job.finishedAt() != null) {
            throw stateConflict("New jobs must start in QUEUED without a lease or attempt");
        }
        CanonicalJsonIntegrity.requireValid(job.requestJson(), job.requestDigest(), "Job request");
        try {
            int inserted = jdbcTemplate.update("""
                            INSERT INTO review_m0_job(
                                job_id, case_id, user_id, job_type, status, request_json, request_digest,
                                input_fingerprint, idempotency_key, progress_completed, progress_total, attempt,
                                lease_owner, lease_expires_at, cancel_requested_at, result_type, result_id,
                                error_code, error_retryable, error_message, version, created_at, started_at,
                                heartbeat_at, finished_at
                            )
                            SELECT ?, review_case.case_id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                   NULL, NULL, NULL, NULL, NULL, ?, ?, NULL, NULL, NULL
                            FROM review_m0_case review_case
                            WHERE review_case.case_id = ? AND review_case.user_id = ?
                            """,
                    job.jobId(), job.userId(), job.jobType().name(), job.status().name(),
                    job.requestJson(), job.requestDigest(), job.inputFingerprint(), job.idempotencyKey(),
                    job.progressCompleted(), job.progressTotal(), job.attempt(), job.leaseOwner(),
                    timestamp(job.leaseExpiresAt()), timestamp(job.cancelRequestedAt()),
                    job.version(), timestamp(job.createdAt()), job.caseId(), job.userId());
            if (inserted != 1) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND,
                        "Review case was not found");
            }
            return job;
        } catch (DuplicateKeyException duplicate) {
            List<ReviewJob> existing = jdbcTemplate.query(
                    "SELECT " + JOB_COLUMNS + " FROM review_m0_job "
                            + "WHERE user_id = ? AND case_id = ? AND job_type = ? AND idempotency_key = ?",
                    jobMapper, job.userId(), job.caseId(), job.jobType().name(), job.idempotencyKey());
            if (existing.size() == 1 && existing.get(0).requestDigest().equals(job.requestDigest())
                    && existing.get(0).requestJson().equals(job.requestJson())) {
                return existing.get(0);
            }
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT,
                    "Job idempotency key was reused with a different payload");
        }
    }

    @Override
    public Optional<ReviewJob> findOwned(String ownerId, String caseId, String jobId) {
        requireOwner(ownerId);
        return jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM review_m0_job "
                        + "WHERE job_id = ? AND case_id = ? AND user_id = ?",
                jobMapper, jobId, caseId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<ReviewJob> acquireNext(String workerId, Instant now, Duration leaseDuration) {
        requireWorker(workerId);
        requireLeaseDuration(leaseDuration);
        return transactions.execute(status -> {
            List<ReviewJob> candidates = jdbcTemplate.query(
                    "SELECT " + JOB_COLUMNS + " FROM review_m0_job "
                            + "WHERE status = 'QUEUED' OR (status = 'RUNNING' AND lease_expires_at <= ?) "
                            + "ORDER BY created_at, job_id LIMIT 32",
                    jobMapper, timestamp(now));
            for (ReviewJob candidate : candidates) {
                int updated = jdbcTemplate.update("""
                                UPDATE review_m0_job
                                SET status = 'RUNNING', lease_owner = ?, lease_expires_at = ?,
                                    attempt = attempt + 1, started_at = COALESCE(started_at, ?),
                                    heartbeat_at = ?, version = version + 1
                                WHERE job_id = ? AND version = ?
                                  AND (status = 'QUEUED' OR (status = 'RUNNING' AND lease_expires_at <= ?))
                                """,
                        workerId, timestamp(now.plus(leaseDuration)), timestamp(now), timestamp(now),
                        candidate.jobId(), candidate.version(), timestamp(now));
                if (updated == 1) {
                    return Optional.of(requireJob(candidate.jobId(), false));
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public ReviewJob heartbeat(
            String jobId,
            String workerId,
            long expectedVersion,
            Instant now,
            Duration leaseDuration
    ) {
        requireWorker(workerId);
        requireLeaseDuration(leaseDuration);
        int updated = jdbcTemplate.update("""
                        UPDATE review_m0_job
                        SET heartbeat_at = ?, lease_expires_at = ?, version = version + 1
                        WHERE job_id = ? AND status = 'RUNNING' AND lease_owner = ?
                          AND lease_expires_at > ? AND version = ?
                        """,
                timestamp(now), timestamp(now.plus(leaseDuration)), jobId, workerId, timestamp(now), expectedVersion);
        if (updated != 1) {
            throw stateConflict("Job lease is not valid for heartbeat");
        }
        return requireJob(jobId, false);
    }

    @Override
    public ReviewJob releaseForRetry(String jobId, String workerId, long expectedVersion, Instant now) {
        requireWorker(workerId);
        int updated = jdbcTemplate.update("""
                        UPDATE review_m0_job
                        SET heartbeat_at = ?, lease_expires_at = ?, version = version + 1
                        WHERE job_id = ? AND status = 'RUNNING' AND lease_owner = ?
                          AND lease_expires_at > ? AND version = ?
                        """,
                timestamp(now), timestamp(now), jobId, workerId, timestamp(now), expectedVersion);
        if (updated != 1) {
            throw stateConflict("Job lease is not valid for retry release");
        }
        return requireJob(jobId, false);
    }

    @Override
    public CheckpointWrite saveCheckpoint(
            String jobId,
            String workerId,
            String checkpointKey,
            String checkpointJson,
            String checkpointDigest,
            long completedUnits,
            long totalUnits,
            Instant now,
            Duration leaseDuration
    ) {
        requireWorker(workerId);
        requireLeaseDuration(leaseDuration);
        if (checkpointKey == null || checkpointKey.isBlank() || checkpointKey.length() > 255
                || checkpointJson == null || !validDigest(checkpointDigest)
                || completedUnits < 0 || totalUnits < completedUnits) {
            throw stateConflict("Checkpoint payload or progress is invalid");
        }
        CanonicalJsonIntegrity.requireValid(checkpointJson, checkpointDigest, "Job checkpoint");
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewJob job = requireJob(jobId, true);
            requireLease(job, workerId, now, ReviewJobStatus.RUNNING);
            List<ReviewJobCheckpoint> existing = jdbcTemplate.query(
                    "SELECT " + CHECKPOINT_COLUMNS + " FROM review_m0_job_checkpoint "
                            + "WHERE job_id = ? AND checkpoint_key = ? FOR UPDATE",
                    checkpointMapper, jobId, checkpointKey);
            boolean replayed = !existing.isEmpty();
            ReviewJobCheckpoint checkpoint;
            if (replayed) {
                checkpoint = existing.get(0);
                if (!checkpoint.checkpointDigest().equals(checkpointDigest)
                        || !checkpoint.checkpointJson().equals(checkpointJson)) {
                    throw stateConflict("Checkpoint key was reused with different content");
                }
            } else {
                jdbcTemplate.update("""
                                INSERT INTO review_m0_job_checkpoint(
                                    job_id, checkpoint_key, checkpoint_json, checkpoint_digest, version, updated_at
                                ) VALUES (?, ?, ?, ?, 0, ?)
                                """,
                        jobId, checkpointKey, checkpointJson, checkpointDigest, timestamp(now));
                checkpoint = new ReviewJobCheckpoint(jobId, checkpointKey, checkpointJson, checkpointDigest, 0, now);
            }
            long nextCompleted = replayed ? job.progressCompleted() : completedUnits;
            if (nextCompleted < job.progressCompleted()) {
                throw stateConflict("Checkpoint progress cannot move backwards");
            }
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_job
                            SET progress_completed = ?, progress_total = ?, heartbeat_at = ?, lease_expires_at = ?,
                                version = version + 1
                            WHERE job_id = ? AND version = ? AND status = 'RUNNING' AND lease_owner = ?
                            """,
                    nextCompleted, totalUnits, timestamp(now), timestamp(now.plus(leaseDuration)),
                    jobId, job.version(), workerId);
            if (updated != 1) {
                throw stateConflict("Job changed concurrently while saving checkpoint");
            }
            return new CheckpointWrite(requireJob(jobId, false), checkpoint, replayed);
        }));
    }

    @Override
    public List<ReviewJobCheckpoint> listCheckpoints(String jobId) {
        return jdbcTemplate.query(
                "SELECT " + CHECKPOINT_COLUMNS + " FROM review_m0_job_checkpoint "
                        + "WHERE job_id = ? ORDER BY checkpoint_key",
                checkpointMapper, jobId);
    }

    @Override
    public ReviewJob requestCancelOwned(String ownerId, String caseId, String jobId, Instant now) {
        requireOwner(ownerId);
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewJob job = requireOwnedLocked(ownerId, caseId, jobId);
            if (job.status().terminal() || job.status() == ReviewJobStatus.CANCEL_REQUESTED) {
                return job;
            }
            ReviewM0StateMachines.requireJobTransition(job.status(), ReviewJobStatus.CANCEL_REQUESTED);
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_job
                            SET status = 'CANCEL_REQUESTED', cancel_requested_at = ?, version = version + 1
                            WHERE job_id = ? AND case_id = ? AND user_id = ? AND version = ?
                              AND status IN ('QUEUED', 'RUNNING')
                            """,
                    timestamp(now), jobId, caseId, ownerId, job.version());
            if (updated != 1) {
                throw stateConflict("Job changed concurrently while requesting cancellation");
            }
            return requireJob(jobId, false);
        }));
    }

    @Override
    public List<ReviewJob> recoverCancellations(Instant now, int maximumJobs) {
        Objects.requireNonNull(now, "now");
        if (maximumJobs < 1 || maximumJobs > 256) {
            throw stateConflict("Cancellation recovery batch size must be between 1 and 256");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            List<ReviewJob> candidates = jdbcTemplate.query(
                    "SELECT " + JOB_COLUMNS + " FROM review_m0_job "
                            + "WHERE status = 'CANCEL_REQUESTED' "
                            + "AND (lease_owner IS NULL OR lease_expires_at <= ?) "
                            + "ORDER BY cancel_requested_at, created_at, job_id LIMIT ?",
                    jobMapper, timestamp(now), maximumJobs);
            List<ReviewJob> recovered = new ArrayList<>(candidates.size());
            for (ReviewJob candidate : candidates) {
                ReviewM0StateMachines.requireJobTransition(
                        candidate.status(), ReviewJobStatus.CANCELLED);
                int updated = jdbcTemplate.update("""
                                UPDATE review_m0_job
                                SET status = 'CANCELLED', lease_owner = NULL, lease_expires_at = NULL,
                                    finished_at = ?, version = version + 1
                                WHERE job_id = ? AND version = ? AND status = 'CANCEL_REQUESTED'
                                  AND (lease_owner IS NULL OR lease_expires_at <= ?)
                                """,
                        timestamp(now), candidate.jobId(), candidate.version(), timestamp(now));
                if (updated == 1) {
                    recovered.add(requireJob(candidate.jobId(), false));
                }
            }
            return List.copyOf(recovered);
        }));
    }

    @Override
    public ReviewJob finalizeCancellation(String jobId, String workerId, Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewJob job = requireJob(jobId, true);
            if (job.status() == ReviewJobStatus.CANCELLED) {
                return job;
            }
            if (job.status() != ReviewJobStatus.CANCEL_REQUESTED) {
                throw stateConflict("Only CANCEL_REQUESTED jobs can become CANCELLED");
            }
            boolean holder = Objects.equals(job.leaseOwner(), workerId);
            boolean unleasedOrExpired = job.leaseOwner() == null || !job.leaseExpiresAt().isAfter(now);
            if (!holder && !unleasedOrExpired) {
                throw stateConflict("Another worker still owns the cancellation lease");
            }
            ReviewM0StateMachines.requireJobTransition(job.status(), ReviewJobStatus.CANCELLED);
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_job
                            SET status = 'CANCELLED', lease_owner = NULL, lease_expires_at = NULL,
                                finished_at = ?, version = version + 1
                            WHERE job_id = ? AND version = ? AND status = 'CANCEL_REQUESTED'
                            """,
                    timestamp(now), jobId, job.version());
            if (updated != 1) {
                throw stateConflict("Job changed concurrently while cancelling");
            }
            return requireJob(jobId, false);
        }));
    }

    @Override
    public ReviewJob fail(String jobId, String workerId, ReviewJobError error, Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewJob job = requireJob(jobId, true);
            if (job.status() == ReviewJobStatus.FAILED) {
                if (job.error().equals(error)) {
                    return job;
                }
                throw stateConflict("FAILED job error cannot be replaced");
            }
            requireLease(job, workerId, now, ReviewJobStatus.RUNNING);
            ReviewM0StateMachines.requireJobTransition(job.status(), ReviewJobStatus.FAILED);
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_job
                            SET status = 'FAILED', error_code = ?, error_retryable = ?, error_message = ?,
                                lease_owner = NULL, lease_expires_at = NULL, finished_at = ?, version = version + 1
                            WHERE job_id = ? AND version = ? AND status = 'RUNNING' AND lease_owner = ?
                            """,
                    error.errorCode().name(), error.retryable(), error.message(), timestamp(now),
                    jobId, job.version(), workerId);
            if (updated != 1) {
                throw stateConflict("Job changed concurrently while failing");
            }
            return requireJob(jobId, false);
        }));
    }

    @Override
    public ReviewJob completeWithResult(
            String jobId,
            String workerId,
            Instant now,
            Supplier<ReviewJobResultRef> databasePublication
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewJob job = requireJob(jobId, true);
            if (job.status() == ReviewJobStatus.SUCCEEDED) {
                return job;
            }
            requireLease(job, workerId, now, ReviewJobStatus.RUNNING);
            ReviewJobResultRef result = Objects.requireNonNull(databasePublication.get(), "publication result");
            ReviewM0StateMachines.requireJobTransition(job.status(), ReviewJobStatus.SUCCEEDED);
            int updated = jdbcTemplate.update("""
                            UPDATE review_m0_job
                            SET status = 'SUCCEEDED', result_type = ?, result_id = ?,
                                progress_completed = progress_total,
                                lease_owner = NULL, lease_expires_at = NULL, finished_at = ?, version = version + 1
                            WHERE job_id = ? AND version = ? AND status = 'RUNNING' AND lease_owner = ?
                            """,
                    result.resultType(), result.resultId(), timestamp(now), jobId, job.version(), workerId);
            if (updated != 1) {
                throw stateConflict("Job changed concurrently while publishing its result");
            }
            return requireJob(jobId, false);
        }));
    }

    private ReviewJob requireOwnedLocked(String ownerId, String caseId, String jobId) {
        List<ReviewJob> rows = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM review_m0_job "
                        + "WHERE job_id = ? AND case_id = ? AND user_id = ? FOR UPDATE",
                jobMapper, jobId, caseId, ownerId);
        if (rows.isEmpty()) {
            throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, "Job was not found");
        }
        return rows.get(0);
    }

    private ReviewJob requireJob(String jobId, boolean forUpdate) {
        List<ReviewJob> rows = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM review_m0_job WHERE job_id = ?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                jobMapper, jobId);
        if (rows.isEmpty()) {
            throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, "Job was not found");
        }
        return rows.get(0);
    }

    private static void requireLease(ReviewJob job, String workerId, Instant now, ReviewJobStatus expectedStatus) {
        if (job.status() != expectedStatus || !Objects.equals(job.leaseOwner(), workerId)
                || job.leaseExpiresAt() == null || !job.leaseExpiresAt().isAfter(now)) {
            throw stateConflict("Worker does not hold a valid job lease");
        }
    }

    private ReviewJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        String resultType = resultSet.getString("result_type");
        String errorCode = resultSet.getString("error_code");
        String requestJson = resultSet.getString("request_json");
        String requestDigest = resultSet.getString("request_digest");
        CanonicalJsonIntegrity.requireValid(requestJson, requestDigest, "Stored job request");
        return new ReviewJob(
                resultSet.getString("job_id"), resultSet.getString("case_id"), resultSet.getString("user_id"),
                ReviewJobType.valueOf(resultSet.getString("job_type")),
                ReviewJobStatus.valueOf(resultSet.getString("status")), requestJson,
                requestDigest, resultSet.getString("input_fingerprint"),
                resultSet.getString("idempotency_key"), resultSet.getLong("progress_completed"),
                resultSet.getLong("progress_total"), resultSet.getInt("attempt"),
                resultSet.getString("lease_owner"), instant(resultSet.getTimestamp("lease_expires_at")),
                instant(resultSet.getTimestamp("cancel_requested_at")),
                resultType == null ? null : new ReviewJobResultRef(resultType, resultSet.getString("result_id")),
                errorCode == null ? null : new ReviewJobError(
                        ReviewM0ErrorCode.valueOf(errorCode),
                        Boolean.TRUE.equals(resultSet.getObject("error_retryable", Boolean.class)),
                        resultSet.getString("error_message")),
                resultSet.getLong("version"), instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("started_at")), instant(resultSet.getTimestamp("heartbeat_at")),
                instant(resultSet.getTimestamp("finished_at")));
    }

    private ReviewJobCheckpoint mapCheckpoint(ResultSet resultSet, int rowNumber) throws SQLException {
        String json = resultSet.getString("checkpoint_json");
        String digest = resultSet.getString("checkpoint_digest");
        CanonicalJsonIntegrity.requireValid(json, digest, "Stored job checkpoint");
        return new ReviewJobCheckpoint(
                resultSet.getString("job_id"), resultSet.getString("checkpoint_key"),
                json, digest,
                resultSet.getLong("version"), resultSet.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 128) {
            throw new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND, "Job was not found");
        }
    }

    private static void requireWorker(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw stateConflict("workerId is invalid");
        }
    }

    private static void requireLeaseDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw stateConflict("Lease duration must be positive");
        }
    }

    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    private static ReviewM0Exception stateConflict(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_JOB_STATE_CONFLICT, message);
    }
}
