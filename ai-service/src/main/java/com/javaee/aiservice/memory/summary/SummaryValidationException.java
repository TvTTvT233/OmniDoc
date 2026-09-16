package com.javaee.aiservice.memory.summary;

public final class SummaryValidationException extends RuntimeException {

    public SummaryValidationException(String message) {
        super(message);
    }

    public SummaryValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
