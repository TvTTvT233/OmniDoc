package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;

public class ReviewM0JobExecutionException extends RuntimeException {
    private final ReviewM0ErrorCode errorCode;
    private final boolean retryable;

    public ReviewM0JobExecutionException(
            ReviewM0ErrorCode errorCode,
            boolean retryable,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public ReviewM0ErrorCode errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
