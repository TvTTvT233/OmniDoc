package com.javaee.aiservice.review.m0.file;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;

public class ContentInspectionException extends Exception {
    private final CannotConfirmReason reason;
    private final String failureStage;

    public ContentInspectionException(CannotConfirmReason reason, String failureStage, String message) {
        super(message);
        this.reason = reason;
        this.failureStage = failureStage;
    }

    public ContentInspectionException(
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
