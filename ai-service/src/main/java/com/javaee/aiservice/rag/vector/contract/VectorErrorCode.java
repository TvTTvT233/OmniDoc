package com.javaee.aiservice.rag.vector.contract;

/**
 * Stable error semantics shared by the local baseline and Qdrant implementations.
 */
public enum VectorErrorCode {
    VECTOR_BACKEND_DISABLED,
    VECTOR_BACKEND_UNAVAILABLE,
    VECTOR_AUTH_FAILED,
    VECTOR_TIMEOUT,
    VECTOR_CONFIGURATION_ERROR,
    VECTOR_DIMENSION_MISMATCH,
    VECTOR_FILTER_REQUIRED,
    VECTOR_UPSERT_FAILED,
    VECTOR_SEARCH_FAILED,
    VECTOR_DELETE_FAILED,
    VECTOR_MIGRATION_FAILED
}
