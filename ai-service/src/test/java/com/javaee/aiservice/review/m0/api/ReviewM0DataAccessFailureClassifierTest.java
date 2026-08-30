package com.javaee.aiservice.review.m0.api;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static com.javaee.aiservice.review.m0.api.ReviewM0DataAccessFailureClassifier.FailureKind;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0DataAccessFailureClassifierTest {

    @Test
    void connectionRefusalAndStartupConnectionFailureAreStorageUnavailable() {
        SQLException refused = new SQLException("synthetic", "08001", 0);
        CannotGetJdbcConnectionException acquisition =
                new CannotGetJdbcConnectionException("synthetic", refused);

        assertThat(ReviewM0DataAccessFailureClassifier.classify(acquisition))
                .isEqualTo(FailureKind.STORAGE_UNAVAILABLE);
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new CannotCreateTransactionException("synthetic", acquisition)))
                .isEqualTo(FailureKind.STORAGE_UNAVAILABLE);
    }

    @Test
    void poolAcquisitionTimeoutWinsOverNestedTransientSqlMarker() {
        RuntimeException poolTimeout = new CannotGetJdbcConnectionException(
                "synthetic", new SQLTransientConnectionException("synthetic"));

        assertThat(ReviewM0DataAccessFailureClassifier.classify(poolTimeout))
                .isEqualTo(FailureKind.STORAGE_UNAVAILABLE);
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new RuntimeException(new CannotAcquireLockException("synthetic", poolTimeout))))
                .isEqualTo(FailureKind.STORAGE_UNAVAILABLE);
    }

    @Test
    void onlyExplicitLockAndSerializationSignalsAreContention() {
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new CannotAcquireLockException("synthetic")))
                .isEqualTo(FailureKind.CONTENTION);
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new SQLException("synthetic", "40001", 0)))
                .isEqualTo(FailureKind.CONTENTION);
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new SQLException("synthetic", "HY000", 1205)))
                .isEqualTo(FailureKind.CONTENTION);
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new SQLException("synthetic", "40001", 1213)))
                .isEqualTo(FailureKind.CONTENTION);
    }

    @Test
    void integrityBusinessAndUnknownFailuresAreNotRetryableContention() {
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new DataIntegrityViolationException("synthetic")))
                .isEqualTo(FailureKind.OTHER);
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT, "synthetic")))
                .isEqualTo(FailureKind.OTHER);
        assertThat(ReviewM0DataAccessFailureClassifier.classify(
                new IllegalStateException("synthetic")))
                .isEqualTo(FailureKind.OTHER);
    }
}
