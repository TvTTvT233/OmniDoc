package com.javaee.aiservice.review.m0.model;

public class ReviewM0Exception extends RuntimeException {
    private final ReviewM0ErrorCode errorCode;

    public ReviewM0Exception(ReviewM0ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ReviewM0Exception(ReviewM0ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ReviewM0ErrorCode errorCode() {
        return errorCode;
    }
}
