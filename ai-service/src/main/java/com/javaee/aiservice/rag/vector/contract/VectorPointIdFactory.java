package com.javaee.aiservice.rag.vector.contract;

/**
 * Produces a deterministic provider ID from tenant scope and the logical record ID.
 */
public interface VectorPointIdFactory {

    String create(VectorRecord record);
}
