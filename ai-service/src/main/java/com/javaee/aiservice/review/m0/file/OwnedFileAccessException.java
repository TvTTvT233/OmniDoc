package com.javaee.aiservice.review.m0.file;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

public class OwnedFileAccessException extends ReviewM0Exception {
    public enum Kind {
        NOT_FOUND,
        SERVICE_UNAVAILABLE,
        SOURCE_UNAVAILABLE,
        INTEGRITY_MISMATCH
    }

    private final Kind kind;

    public OwnedFileAccessException(Kind kind, String message) {
        super(errorCode(kind), message);
        this.kind = kind;
    }

    public OwnedFileAccessException(Kind kind, String message, Throwable cause) {
        super(errorCode(kind), message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    private static ReviewM0ErrorCode errorCode(Kind kind) {
        return switch (kind) {
            case NOT_FOUND -> ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND;
            case SERVICE_UNAVAILABLE, SOURCE_UNAVAILABLE, INTEGRITY_MISMATCH ->
                    ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE;
        };
    }
}
