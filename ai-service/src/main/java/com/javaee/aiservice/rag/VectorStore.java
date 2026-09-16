package com.javaee.aiservice.rag;

import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorBackendHealth;
import com.javaee.aiservice.rag.vector.contract.VectorBackendRouter;
import com.javaee.aiservice.rag.vector.contract.VectorBackendType;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorMatch;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;
import com.javaee.aiservice.rag.vector.contract.VectorStoreMode;
import com.javaee.aiservice.rag.vector.local.LegacyVectorOperations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable vector-store facade.
 *
 * <p>The strongly typed methods expose the frozen backend contract. The legacy methods remain so
 * existing RAG callers can migrate independently from the backend implementation.</p>
 */
@Component
public class VectorStore {

    private static final String DEFAULT_USER_ID = "system";
    private static final String DEFAULT_KNOWLEDGE_BASE_ID = "default";

    private final VectorBackendRouter backendRouter;

    @Value("${ai.vector.embedding.model:text-embedding-v1}")
    private String defaultEmbeddingModel;

    @Value("${ai.vector.embedding.schema-version:1}")
    private int defaultSchemaVersion;

    public VectorStore(VectorBackendRouter backendRouter) {
        if (backendRouter == null) {
            throw new IllegalArgumentException("backendRouter must not be null");
        }
        this.backendRouter = backendRouter;
    }

    public VectorBackendType type() {
        return backendRouter.type();
    }

    public VectorStoreMode mode() {
        return backendRouter.mode();
    }

    public void upsert(VectorRecord record) {
        backendRouter.upsert(record);
    }

    public void upsertBatch(List<VectorRecord> records) {
        backendRouter.upsertBatch(records);
    }

    public List<VectorMatch> search(VectorQuery query) {
        return backendRouter.search(query);
    }

    public void deleteById(String id, VectorScope scope) {
        backendRouter.deleteById(id, scope);
    }

    public long deleteByDocument(String documentId, VectorScope scope) {
        return backendRouter.deleteByDocument(documentId, scope);
    }

    public VectorBackendHealth health() {
        return backendRouter.health();
    }

    /**
     * Legacy write entry point retained for existing KnowledgeBase callers.
     */
    public void store(String id, float[] vector, Map<String, Object> metadata) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        Map<String, Object> legacyMetadata = mutableCopy(metadata);
        VectorScope scope = new VectorScope(
                textValue(legacyMetadata.remove("userId"), DEFAULT_USER_ID),
                textValue(legacyMetadata.remove("knowledgeBaseId"), DEFAULT_KNOWLEDGE_BASE_ID));
        String documentId = textValue(legacyMetadata.remove("documentId"), id);
        EmbeddingSpec embedding = embeddingSpec(legacyMetadata, vector.length);

        legacyMetadata.remove("id");
        legacyMetadata.remove("embeddingModel");
        legacyMetadata.remove("embeddingDimension");
        legacyMetadata.remove("schemaVersion");
        upsert(new VectorRecord(id, documentId, vector, scope, embedding, legacyMetadata));
    }

    public List<Map<String, Object>> search(float[] queryVector, int topK) {
        return search(queryVector, topK, Collections.emptyMap());
    }

    /**
     * Legacy query entry point. Complete tenant filters are promoted to the frozen typed contract;
     * unscoped legacy queries use the baseline-only compatibility bridge until their callers migrate.
     */
    public List<Map<String, Object>> search(float[] queryVector, int topK, Map<String, Object> filters) {
        Map<String, Object> legacyFilters = mutableCopy(filters);
        EmbeddingSpec embedding = embeddingSpec(legacyFilters, queryVector.length);
        String userId = nullableText(legacyFilters.get("userId"));
        String knowledgeBaseId = nullableText(legacyFilters.get("knowledgeBaseId"));

        List<VectorMatch> matches;
        if (userId != null && knowledgeBaseId != null) {
            String documentId = nullableText(legacyFilters.remove("documentId"));
            legacyFilters.remove("userId");
            legacyFilters.remove("knowledgeBaseId");
            VectorFilter filter = new VectorFilter(
                    new VectorScope(userId, knowledgeBaseId), documentId, legacyFilters);
            matches = search(new VectorQuery(queryVector, topK, filter, embedding));
        } else {
            matches = legacyOperations().searchLegacy(queryVector, topK, legacyFilters, embedding);
        }
        return toLegacyResults(matches);
    }

    /**
     * Legacy delete entry point retained for callers that only have a logical record id.
     */
    public void delete(String id) {
        legacyOperations().deleteLegacy(id);
    }

    private LegacyVectorOperations legacyOperations() {
        if (backendRouter instanceof LegacyVectorOperations operations) {
            return operations;
        }
        throw new IllegalStateException("Current vector backend does not support legacy unscoped operations");
    }

    private EmbeddingSpec embeddingSpec(Map<String, Object> values, int actualDimension) {
        String model = textValue(values.get("embeddingModel"), defaultEmbeddingModel);
        int dimension = intValue(values.get("embeddingDimension"), actualDimension, "embeddingDimension");
        int schemaVersion = intValue(values.get("schemaVersion"), defaultSchemaVersion, "schemaVersion");
        return new EmbeddingSpec(model, dimension, schemaVersion);
    }

    private static List<Map<String, Object>> toLegacyResults(List<VectorMatch> matches) {
        List<Map<String, Object>> results = new ArrayList<>(matches.size());
        for (VectorMatch match : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", match.id());
            item.put("similarity", match.score());
            item.putAll(match.metadata());
            results.add(item);
        }
        return results;
    }

    private static Map<String, Object> mutableCopy(Map<String, Object> values) {
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private static String textValue(Object value, String fallback) {
        String text = nullableText(value);
        return text == null ? fallback : text;
    }

    private static String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static int intValue(Object value, int fallback, String field) {
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer", e);
        }
    }
}
