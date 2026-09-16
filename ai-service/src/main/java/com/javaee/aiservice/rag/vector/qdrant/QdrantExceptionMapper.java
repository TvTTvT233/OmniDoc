package com.javaee.aiservice.rag.vector.qdrant;

import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

final class QdrantExceptionMapper {

    private QdrantExceptionMapper() {
    }

    static VectorBackendException map(
            Throwable failure,
            VectorErrorCode fallback,
            String operation
    ) {
        if (failure instanceof VectorBackendException backendException) {
            return backendException;
        }
        Throwable root = unwrap(failure);
        Status.Code status = grpcStatus(root);
        if (root instanceof TimeoutException
                || root instanceof SocketTimeoutException
                || status == Status.Code.DEADLINE_EXCEEDED) {
            return exception(VectorErrorCode.VECTOR_TIMEOUT,
                    "Qdrant " + operation + " timed out", true, failure);
        }
        if (status == Status.Code.UNAUTHENTICATED || status == Status.Code.PERMISSION_DENIED) {
            return exception(VectorErrorCode.VECTOR_AUTH_FAILED,
                    "Qdrant authentication failed", false, failure);
        }
        if (root instanceof ConnectException
                || status == Status.Code.UNAVAILABLE
                || status == Status.Code.RESOURCE_EXHAUSTED
                || status == Status.Code.CANCELLED) {
            return exception(VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE,
                    "Qdrant is unavailable", true, failure);
        }
        if (status == Status.Code.INVALID_ARGUMENT
                || status == Status.Code.FAILED_PRECONDITION
                || status == Status.Code.NOT_FOUND
                || status == Status.Code.ALREADY_EXISTS) {
            return exception(VectorErrorCode.VECTOR_CONFIGURATION_ERROR,
                    "Qdrant configuration is incompatible", false, failure);
        }
        boolean retryable = fallback == VectorErrorCode.VECTOR_BACKEND_UNAVAILABLE
                || fallback == VectorErrorCode.VECTOR_TIMEOUT;
        return exception(fallback, "Qdrant " + operation + " failed", retryable, failure);
    }

    static boolean isAlreadyExists(Throwable failure) {
        return grpcStatus(unwrap(failure)) == Status.Code.ALREADY_EXISTS;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if ((current instanceof QdrantClientCallException
                    || current instanceof ExecutionException
                    || current instanceof CompletionException)
                    && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            return current;
        }
        return failure;
    }

    private static Status.Code grpcStatus(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof StatusRuntimeException statusException) {
                return statusException.getStatus().getCode();
            }
            if (current instanceof StatusException statusException) {
                return statusException.getStatus().getCode();
            }
            current = current.getCause();
        }
        return Status.Code.UNKNOWN;
    }

    private static VectorBackendException exception(
            VectorErrorCode code,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        return new VectorBackendException(
                VectorBackendType.QDRANT,
                code,
                message,
                retryable,
                cause);
    }
}
