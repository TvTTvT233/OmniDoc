package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJob;
import com.javaee.aiservice.review.m0.model.ReviewJobError;
import com.javaee.aiservice.review.m0.model.ReviewJobStatus;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class ReviewM0JobDispatcher {
    private final ReviewM0JobStore jobStore;
    private final ReviewM0JobHandlerRegistry handlers;

    public ReviewM0JobDispatcher(ReviewM0JobStore jobStore, ReviewM0JobHandlerRegistry handlers) {
        this.jobStore = jobStore;
        this.handlers = handlers;
    }

    public Optional<ReviewJob> runNext(
            String workerId,
            Instant now,
            Duration leaseDuration,
            int maximumAttempts
    ) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        Optional<ReviewJob> acquired = jobStore.acquireNext(workerId, now, leaseDuration);
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        ReviewJob leased = acquired.get();
        ReviewM0JobHandler handler = handlers.find(leased.jobType()).orElse(null);
        if (handler == null) {
            return Optional.of(jobStore.fail(
                    leased.jobId(), workerId,
                    new ReviewJobError(
                            ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR,
                            false,
                            "No Review M0 handler is registered for " + leased.jobType()),
                    now));
        }
        try {
            handler.handle(leased, jobStore);
            ReviewJob after = jobStore.findOwned(leased.userId(), leased.caseId(), leased.jobId()).orElseThrow();
            if (after.status() == ReviewJobStatus.CANCEL_REQUESTED) {
                return Optional.of(jobStore.finalizeCancellation(after.jobId(), workerId, now));
            }
            if (!after.status().terminal()) {
                return Optional.of(jobStore.fail(
                        leased.jobId(), workerId,
                        new ReviewJobError(
                                ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR,
                                false,
                                "Review M0 handler returned without a terminal state"),
                        now));
            }
            return Optional.of(after);
        } catch (ReviewM0JobExecutionException error) {
            ReviewJob current = jobStore.findOwned(leased.userId(), leased.caseId(), leased.jobId()).orElseThrow();
            if (current.status() == ReviewJobStatus.CANCEL_REQUESTED) {
                return Optional.of(jobStore.finalizeCancellation(current.jobId(), workerId, now));
            }
            if (error.retryable() && current.attempt() < maximumAttempts) {
                return Optional.of(jobStore.releaseForRetry(
                        current.jobId(), workerId, current.version(), now));
            }
            return Optional.of(jobStore.fail(
                    current.jobId(), workerId,
                    new ReviewJobError(error.errorCode(), error.retryable(), safeMessage(error)),
                    now));
        } catch (RuntimeException error) {
            ReviewJob current = jobStore.findOwned(leased.userId(), leased.caseId(), leased.jobId()).orElseThrow();
            if (current.status().terminal()) {
                return Optional.of(current);
            }
            if (current.status() == ReviewJobStatus.CANCEL_REQUESTED) {
                return Optional.of(jobStore.finalizeCancellation(current.jobId(), workerId, now));
            }
            return Optional.of(jobStore.fail(
                    current.jobId(), workerId,
                    new ReviewJobError(
                            ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR,
                            false,
                            "Review M0 handler failed"),
                    now));
        }
    }

    private static String safeMessage(ReviewM0JobExecutionException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "Review M0 handler failed";
        }
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
