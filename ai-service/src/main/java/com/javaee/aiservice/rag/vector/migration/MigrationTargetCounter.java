package com.javaee.aiservice.rag.vector.migration;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;

/**
 * Internal QV02 integration seam for an exact target count in one embedding space.
 */
@FunctionalInterface
public interface MigrationTargetCounter {

    long count(EmbeddingSpec embedding);
}
