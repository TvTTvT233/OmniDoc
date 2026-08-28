package com.javaee.aiservice.review.m0.idempotency;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

/** Internal signal that a same-payload caller must follow the database winner. */
public final class IdempotencyInProgressException extends ReviewM0Exception {
    public IdempotencyInProgressException(ReviewM0ErrorCode fallbackCode) {
        super(fallbackCode, "The idempotent write is still in progress");
    }
}
