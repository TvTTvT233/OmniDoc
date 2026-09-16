package com.javaee.aiservice.rag.vector.contract;

/**
 * Runtime routing boundary used by the stable VectorStore facade.
 * {@link #type()} identifies the backend whose result semantics are returned:
 * local for baseline/shadow and Qdrant for qdrant mode.
 */
public interface VectorBackendRouter extends VectorBackend {

    VectorStoreMode mode();
}
