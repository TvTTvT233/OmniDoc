package com.javaee.aiservice.memory.summary;

public final class SummaryGenerationException extends RuntimeException {

    public SummaryGenerationException(String message) {
        super(message);
    }

    public SummaryGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
