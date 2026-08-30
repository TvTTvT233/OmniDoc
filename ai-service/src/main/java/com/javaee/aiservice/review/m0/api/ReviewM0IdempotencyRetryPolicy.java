package com.javaee.aiservice.review.m0.api;

import java.time.Duration;

/** Review-only operational bounds; these are safety limits, not a product SLA. */
public record ReviewM0IdempotencyRetryPolicy(
        int maximumTransactionAttempts,
        Duration totalWaitBudget,
        Duration transactionTimeout
) {
    public static final int DEFAULT_MAXIMUM_TRANSACTION_ATTEMPTS = 24;
    public static final Duration DEFAULT_TOTAL_WAIT_BUDGET = Duration.ofSeconds(8);
    public static final Duration DEFAULT_TRANSACTION_TIMEOUT = Duration.ofSeconds(8);

    public ReviewM0IdempotencyRetryPolicy {
        if (maximumTransactionAttempts < 1 || maximumTransactionAttempts > 64) {
            throw new IllegalArgumentException("Idempotency transaction attempt limit is invalid");
        }
        if (totalWaitBudget == null || totalWaitBudget.isZero() || totalWaitBudget.isNegative()
                || totalWaitBudget.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Idempotency total wait budget is invalid");
        }
        if (transactionTimeout == null || transactionTimeout.isZero() || transactionTimeout.isNegative()
                || transactionTimeout.compareTo(totalWaitBudget) > 0) {
            throw new IllegalArgumentException("Idempotency transaction timeout is invalid");
        }
    }

    public static ReviewM0IdempotencyRetryPolicy defaults() {
        return new ReviewM0IdempotencyRetryPolicy(
                DEFAULT_MAXIMUM_TRANSACTION_ATTEMPTS,
                DEFAULT_TOTAL_WAIT_BUDGET,
                DEFAULT_TRANSACTION_TIMEOUT);
    }
}
