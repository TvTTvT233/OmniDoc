package com.javaee.aiservice.rag.vector.local;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;

import java.util.List;
import java.util.Map;

/**
 * Compatibility bridge for pre-contract callers that do not yet supply a complete tenant scope.
 * New code must use the scoped {@code VectorBackend} methods instead.
 */
public interface LegacyVectorOperations {

    List<VectorMatch> searchLegacy(float[] vector, int topK, Map<String, Object> filters,
                                   EmbeddingSpec embedding);

    void deleteLegacy(String id);
}
