package com.javaee.aiservice.review.m0.review;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class BoundedRegexMatcher implements SafeRegexMatcher {
    private final Duration timeout;

    public BoundedRegexMatcher(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Regex timeout must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    public boolean matches(String expression, boolean caseSensitive, String value) {
        final Pattern pattern;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(expression, flags);
        } catch (PatternSyntaxException error) {
            throw system("Published REGEX rule cannot be compiled");
        }
        FutureTask<Boolean> task = new FutureTask<>(() -> pattern.matcher(value).matches());
        Thread worker = new Thread(task, "review-m0-regex");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            throw system("Published REGEX rule exceeded its execution boundary");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw system("REGEX evaluation was interrupted");
        } catch (ExecutionException error) {
            throw system("Published REGEX rule failed during evaluation");
        }
    }

    private static ReviewM0Exception system(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR, message);
    }
}
