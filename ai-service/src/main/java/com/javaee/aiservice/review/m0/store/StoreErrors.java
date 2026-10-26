package com.javaee.aiservice.review.m0.store;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

final class StoreErrors {
    private StoreErrors() {
    }

    static ReviewM0Exception caseNotFound() {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND, "Review case was not found");
    }

    static ReviewM0Exception resourceNotFound(String resource) {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                resource + " was not found");
    }

    static ReviewM0Exception versionConflict(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT, message);
    }

    static ReviewM0Exception storage(String message, Throwable cause) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE, message, cause);
    }
}
