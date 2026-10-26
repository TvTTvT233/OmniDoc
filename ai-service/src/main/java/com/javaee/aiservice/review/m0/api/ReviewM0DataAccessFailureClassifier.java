package com.javaee.aiservice.review.m0.api;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

/** Classifies database failures without inspecting or exposing exception messages. */
public final class ReviewM0DataAccessFailureClassifier {
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;

    private ReviewM0DataAccessFailureClassifier() {
    }

    public static FailureKind classify(Throwable failure) {
        boolean contention = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (isStorageUnavailable(current)) {
                return FailureKind.STORAGE_UNAVAILABLE;
            }
            if (isContention(current)) {
                contention = true;
            }
        }
        return contention ? FailureKind.CONTENTION : FailureKind.OTHER;
    }

    private static boolean isStorageUnavailable(Throwable failure) {
        if (failure instanceof CannotGetJdbcConnectionException
                || failure instanceof CannotCreateTransactionException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof SQLTransientConnectionException
                || failure instanceof SQLNonTransientConnectionException
                || failure instanceof SQLRecoverableException) {
            return true;
        }
        if (failure instanceof SQLException sql) {
            String state = sql.getSQLState();
            return state != null && state.startsWith("08");
        }
        return false;
    }

    private static boolean isContention(Throwable failure) {
        if (failure instanceof CannotAcquireLockException
                || failure instanceof CannotSerializeTransactionException
                || failure instanceof ConcurrencyFailureException) {
            return true;
        }
        if (failure instanceof SQLException sql) {
            String state = sql.getSQLState();
            return "40001".equals(state)
                    || sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT
                    || sql.getErrorCode() == MYSQL_DEADLOCK;
        }
        return false;
    }

    public enum FailureKind {
        CONTENTION,
        STORAGE_UNAVAILABLE,
        OTHER
    }
}
