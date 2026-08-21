package com.javaee.aiservice.review.m0.api;

import com.javaee.aiservice.review.m0.file.OwnedFileAccessException;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.javaee.aiservice.review.m0.api.ReviewM0ApiDtos.ErrorResponse;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReviewM0Controller.class)
public final class ReviewM0ApiExceptionHandler {

    @ExceptionHandler(ReviewM0ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> resource(
            ReviewM0ResourceNotFoundException error, HttpServletRequest request
    ) {
        return error(error.errorCode(), details("resourceType", error.resourceType()), request);
    }

    @ExceptionHandler(ReviewM0Exception.class)
    public ResponseEntity<ErrorResponse> review(ReviewM0Exception error, HttpServletRequest request) {
        return error(error.errorCode(), safeDetails(error.errorCode(), request), request);
    }

    @ExceptionHandler(OwnedFileAccessException.class)
    public ResponseEntity<ErrorResponse> file(OwnedFileAccessException error, HttpServletRequest request) {
        ReviewM0ErrorCode code = error.kind() == OwnedFileAccessException.Kind.NOT_FOUND
                ? ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND
                : ReviewM0ErrorCode.REVIEW_M0_FILE_SERVICE_UNAVAILABLE;
        return error(code, safeDetails(code, request), request);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> authentication(SecurityException error, HttpServletRequest request) {
        return error(ReviewM0ErrorCode.AUTHENTICATION_REQUIRED, Map.of(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error, HttpServletRequest request) {
        return error(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID, Map.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException error,
                                                    HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ErrorResponse(
                ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID,
                message(ReviewM0ErrorCode.REVIEW_M0_REQUEST_INVALID), requestId(request), Map.of()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> storage(DataAccessException error, HttpServletRequest request) {
        return error(ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE, Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> internal(Exception error, HttpServletRequest request) {
        return error(ReviewM0ErrorCode.REVIEW_M0_INTERNAL_ERROR, Map.of(), request);
    }

    private static ResponseEntity<ErrorResponse> error(
            ReviewM0ErrorCode code, Map<String, Object> details, HttpServletRequest request
    ) {
        return ResponseEntity.status(status(code)).body(
                new ErrorResponse(code, message(code), requestId(request), Map.copyOf(details)));
    }

    private static HttpStatus status(ReviewM0ErrorCode code) {
        return switch (code) {
            case REVIEW_M0_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case REVIEW_M0_CASE_NOT_FOUND, REVIEW_M0_RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REVIEW_M0_VERSION_CONFLICT, REVIEW_M0_IDEMPOTENCY_CONFLICT,
                    REVIEW_M0_BASIS_REVISION_STALE, REVIEW_M0_BASIS_CANNOT_CONFIRM,
                    REVIEW_M0_CHECKLIST_NOT_CONFIRMED, REVIEW_M0_CHECKLIST_STALE_BASIS,
                    REVIEW_M0_CHECKLIST_ACK_MISMATCH, REVIEW_M0_CONDITIONAL_UNRESOLVED,
                    REVIEW_M0_UPLOAD_NOT_CONFIRMED, REVIEW_M0_REMOTE_PREVIEW_STALE,
                    REVIEW_M0_REMOTE_CONSENT_REQUIRED, REVIEW_M0_REMOTE_CONSENT_CONSUMED,
                    REVIEW_M0_JOB_STATE_CONFLICT, REVIEW_M0_INCREMENTAL_BASE_MISMATCH -> HttpStatus.CONFLICT;
            case REVIEW_M0_UNSUPPORTED_FILE_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case REVIEW_M0_FILE_SERVICE_UNAVAILABLE, REVIEW_M0_REMOTE_MODEL_FAILED,
                    REVIEW_M0_REMOTE_OUTCOME_UNKNOWN, REVIEW_M0_STORAGE_UNAVAILABLE,
                    REVIEW_M0_JOB_QUEUE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case REVIEW_M0_INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String message(ReviewM0ErrorCode code) {
        return switch (code) {
            case REVIEW_M0_REQUEST_INVALID -> "Review M0 request is invalid";
            case AUTHENTICATION_REQUIRED -> "Authentication is required";
            case REVIEW_M0_CASE_NOT_FOUND -> "Review case was not found";
            case REVIEW_M0_RESOURCE_NOT_FOUND -> "Review M0 resource was not found";
            case REVIEW_M0_VERSION_CONFLICT -> "The resource changed concurrently";
            case REVIEW_M0_IDEMPOTENCY_CONFLICT -> "Idempotency key conflicts with another request";
            case REVIEW_M0_BASIS_REVISION_STALE -> "Official basis revision is stale";
            case REVIEW_M0_BASIS_CANNOT_CONFIRM -> "Official basis cannot be confirmed";
            case REVIEW_M0_CHECKLIST_NOT_CONFIRMED -> "A current confirmed checklist is required";
            case REVIEW_M0_CHECKLIST_STALE_BASIS -> "The checklist is stale after basis changes";
            case REVIEW_M0_CHECKLIST_ACK_MISMATCH -> "Checklist acknowledgement does not match";
            case REVIEW_M0_CONDITIONAL_UNRESOLVED -> "A checklist condition requires a user decision";
            case REVIEW_M0_UPLOAD_NOT_CONFIRMED -> "A current upload confirmation is required";
            case REVIEW_M0_REMOTE_PREVIEW_STALE -> "Remote preview is stale";
            case REVIEW_M0_REMOTE_CONSENT_REQUIRED -> "Remote consent is required";
            case REVIEW_M0_REMOTE_CONSENT_CONSUMED -> "Remote consent was already consumed";
            case REVIEW_M0_JOB_STATE_CONFLICT -> "Job state does not allow this operation";
            case REVIEW_M0_INCREMENTAL_BASE_MISMATCH -> "Incremental review base does not match";
            case REVIEW_M0_UNSUPPORTED_FILE_TYPE -> "File type is not supported";
            case REVIEW_M0_FILE_SERVICE_UNAVAILABLE -> "Owned file service is unavailable";
            case REVIEW_M0_REMOTE_MODEL_FAILED -> "Remote model request failed";
            case REVIEW_M0_REMOTE_OUTCOME_UNKNOWN -> "Remote model outcome is unknown";
            case REVIEW_M0_STORAGE_UNAVAILABLE -> "Review M0 storage is unavailable";
            case REVIEW_M0_JOB_QUEUE_UNAVAILABLE -> "Review M0 Job queue is unavailable";
            case REVIEW_M0_INTERNAL_ERROR -> "Review M0 encountered an internal error";
        };
    }

    private static Map<String, Object> safeDetails(ReviewM0ErrorCode code, HttpServletRequest request) {
        if (code == ReviewM0ErrorCode.REVIEW_M0_RESOURCE_NOT_FOUND) {
            return details("resourceType", resourceType(request.getRequestURI()));
        }
        return Map.of();
    }

    private static String resourceType(String path) {
        if (path.contains("/jobs/")) return "JOB";
        if (path.contains("/reports/")) return path.contains("/findings/") ? "FINDING" : "REPORT";
        if (path.contains("/materials/")) return "MATERIAL";
        if (path.contains("/submission-sources/")) return "SUBMISSION_SOURCE";
        if (path.contains("/checklist-snapshots/")) return "CHECKLIST_SNAPSHOT";
        if (path.contains("/checklist-draft/requirements/")) return "REQUIREMENT";
        if (path.contains("/remote-previews/")) return "REMOTE_PREVIEW";
        if (path.contains("/official-bases/")) return "OFFICIAL_BASIS";
        return "RESOURCE";
    }

    private static Map<String, Object> details(String key, Object value) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (value != null) result.put(key, value);
        return result;
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(ReviewM0RequestIdFilter.ATTRIBUTE);
        return value instanceof String text && !text.isBlank()
                ? text : "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
