package com.javaee.aiservice.review.m0.archive;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;

import java.io.IOException;

public class ArchiveEntryReadException extends IOException {
    private final CannotConfirmReason reason;
    private final String failureStage;

    public ArchiveEntryReadException(CannotConfirmReason reason, String failureStage, String message) {
        super(message);
        this.reason = reason;
        this.failureStage = failureStage;
    }

    public ArchiveEntryReadException(
            CannotConfirmReason reason,
            String failureStage,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = reason;
        this.failureStage = failureStage;
    }

    public CannotConfirmReason reason() {
        return reason;
    }

    public String failureStage() {
        return failureStage;
    }
}
