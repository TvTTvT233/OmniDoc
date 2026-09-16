package com.javaee.aiservice.rag.vector.contract;

/**
 * Provider-neutral exception exposed by vector backend adapters.
 */
public class VectorBackendException extends RuntimeException {

    private final VectorBackendType backend;
    private final VectorErrorCode errorCode;
    private final boolean retryable;

    public VectorBackendException(VectorBackendType backend, VectorErrorCode errorCode,
                                  String message, boolean retryable, Throwable cause) {
        super(message, cause);
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        this.backend = backend;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public VectorBackendException(VectorBackendType backend, VectorErrorCode errorCode,
                                  String message, boolean retryable) {
        this(backend, errorCode, message, retryable, null);
    }

    public VectorBackendType getBackend() {
        return backend;
    }

    public VectorErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
