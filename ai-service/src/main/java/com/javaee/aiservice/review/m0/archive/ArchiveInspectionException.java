package com.javaee.aiservice.review.m0.archive;

import com.javaee.aiservice.review.m0.model.CannotConfirmReason;

public class ArchiveInspectionException extends Exception {
    private final CannotConfirmReason reason;
    private final String failureStage;
    private final Integer entryIndex;
    private final String entryNameDigest;

    public ArchiveInspectionException(
            CannotConfirmReason reason,
            String failureStage,
            Integer entryIndex,
            String entryNameDigest,
            String message
    ) {
        super(message);
        this.reason = reason;
        this.failureStage = failureStage;
        this.entryIndex = entryIndex;
        this.entryNameDigest = entryNameDigest;
    }

    public ArchiveInspectionException(
            CannotConfirmReason reason,
            String failureStage,
            Integer entryIndex,
            String entryNameDigest,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = reason;
        this.failureStage = failureStage;
        this.entryIndex = entryIndex;
        this.entryNameDigest = entryNameDigest;
    }

    public CannotConfirmReason reason() {
        return reason;
    }

    public String failureStage() {
        return failureStage;
    }

    public Integer entryIndex() {
        return entryIndex;
    }

    public String entryNameDigest() {
        return entryNameDigest;
    }
}
