package com.javaee.aiservice.rag.vector.migration;

import com.javaee.aiservice.rag.vector.contract.VectorBackendException;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorErrorCode;

public final class VectorMigrationException extends VectorBackendException {

    private final MigrationReport report;

    public VectorMigrationException(String safeMessage, boolean retryable, Throwable cause, MigrationReport report) {
        super(
                VectorBackendType.QDRANT,
                VectorErrorCode.VECTOR_MIGRATION_FAILED,
                safeMessage,
                retryable,
                cause
        );
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        this.report = report;
    }

    public MigrationReport report() {
        return report;
    }
}
