package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;

final class MaterialErrors {
    private MaterialErrors() {
    }

    static ReviewM0Exception invalid(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, message);
    }

    static ReviewM0Exception unsupported() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_UNSUPPORTED_FILE_TYPE,
                "Top-level file extension is not supported by Review M0");
    }

    static ReviewM0Exception caseNotFound() {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_CASE_NOT_FOUND,
                "Review case does not exist");
    }

    static ReviewM0Exception resourceNotFound() {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND,
                "Review resource does not exist");
    }

    static ReviewM0Exception versionConflict(String message) {
        return new ReviewM0Exception(ReviewM0ErrorCode.REVIEW_M0_VERSION_CONFLICT, message);
    }

    static ReviewM0Exception uploadNotConfirmed() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_UPLOAD_NOT_CONFIRMED,
                "A current upload confirmation is required");
    }

    static ReviewM0Exception checklistNotConfirmed() {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_CHECKLIST_NOT_CONFIRMED,
                "A current confirmed checklist snapshot is required");
    }

    static ReviewM0Exception storageUnavailable(Throwable cause) {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                "Review M0 material storage is unavailable",
                cause);
    }

    static ReviewM0Exception fileUnavailable(Throwable cause) {
        return new ReviewM0Exception(
                ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE,
                "Owned file access is unavailable",
                cause);
    }
}
