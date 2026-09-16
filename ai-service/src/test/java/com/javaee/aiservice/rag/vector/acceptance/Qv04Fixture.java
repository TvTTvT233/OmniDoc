package com.javaee.aiservice.rag.vector.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.vector.contract.EmbeddingSpec;
import com.javaee.aiservice.rag.vector.contract.VectorFilter;
import com.javaee.aiservice.rag.vector.contract.VectorQuery;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import com.javaee.aiservice.rag.vector.contract.VectorScope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads and expands the fully synthetic QV04 fixture without contacting an embedding service.
 */
public final class Qv04Fixture {

    private static final String RESOURCE = "/qdrant/qv04-vector-fixture.json";

    private final FixtureDefinition definition;
    private final EmbeddingSpec embedding;
    private final Map<String, ScopeDefinition> scopesByRef;
    private final Map<Integer, SegmentTemplate> templatesByOrdinal;
    private final List<ExpandedRecord> records;
    private final Map<String, ExpandedRecord> recordsById;
    private final List<QueryCase> queries;
    private final List<DeletionCase> deletions;

    private Qv04Fixture(FixtureDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.embedding = new EmbeddingSpec(
                definition.embedding().model(),
                definition.embedding().dimension(),
                definition.embedding().schemaVersion());
        this.scopesByRef = definition.scopes().stream().collect(Collectors.toUnmodifiableMap(
                ScopeDefinition::id, Function.identity()));
        this.templatesByOrdinal = definition.segmentTemplates().stream().collect(Collectors.toUnmodifiableMap(
                SegmentTemplate::ordinal, Function.identity()));
        this.records = expandRecords();
        this.recordsById = records.stream().collect(Collectors.toUnmodifiableMap(
                item -> item.record().id(), Function.identity()));
        this.queries = definition.queries().stream().map(this::expandQuery).toList();
        this.deletions = definition.deletions().stream().map(this::expandDeletion).toList();
    }

    public static Qv04Fixture load() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = Qv04Fixture.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing QV04 fixture resource: " + RESOURCE);
            }
            return new Qv04Fixture(objectMapper.readValue(input, FixtureDefinition.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load QV04 fixture", exception);
        }
    }

    public FixtureDefinition definition() {
        return definition;
    }

    public EmbeddingSpec embedding() {
        return embedding;
    }

    public List<ExpandedRecord> records() {
        return records;
    }

    public Map<String, ExpandedRecord> recordsById() {
        return recordsById;
    }

    public List<QueryCase> queries() {
        return queries;
    }

    public List<DeletionCase> deletions() {
        return deletions;
    }

    public List<ExpandedRecord> recordsForScope(VectorScope scope) {
        return records.stream().filter(item -> item.record().scope().equals(scope)).toList();
    }

    public float[] vectorFor(String semanticKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((definition.generator().seed() + "\n" + semanticKey)
                    .getBytes(StandardCharsets.UTF_8));
            float[] vector = new float[embedding.dimension()];
            double norm = 0.0d;
            for (int index = 0; index < vector.length; index++) {
                float value = ((bytes[index % bytes.length] & 0xff) - 127.5f) / 127.5f;
                vector[index] = value;
                norm += value * value;
            }
            double divisor = Math.sqrt(norm);
            if (divisor == 0.0d) {
                vector[0] = 1.0f;
                return vector;
            }
            for (int index = 0; index < vector.length; index++) {
                vector[index] = (float) (vector[index] / divisor);
            }
            return vector;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }

    public static double cosine(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("vectors must have equal dimensions");
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<ExpandedRecord> expandRecords() {
        List<ExpandedRecord> expanded = new ArrayList<>();
        for (ScopeDefinition scopeDefinition : definition.scopes()) {
            VectorScope scope = new VectorScope(scopeDefinition.userId(), scopeDefinition.knowledgeBaseId());
            for (SegmentTemplate template : definition.segmentTemplates()) {
                String recordId = recordId(scopeDefinition.id(), template.ordinal());
                String documentId = documentId(scopeDefinition.id(), template.documentSlot());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("fixtureId", definition.fixtureId());
                metadata.put("segmentIndex", template.ordinal());
                metadata.put("synthetic", true);
                VectorRecord record = new VectorRecord(
                        recordId,
                        documentId,
                        vectorFor(template.semanticKey()),
                        scope,
                        embedding,
                        metadata);
                expanded.add(new ExpandedRecord(
                        record,
                        scopeDefinition.id(),
                        template.ordinal(),
                        template.documentSlot(),
                        template.semanticKey(),
                        template.text()));
            }
        }
        return List.copyOf(expanded);
    }

    private QueryCase expandQuery(QueryDefinition raw) {
        ScopeDefinition scopeDefinition = requireScope(raw.scopeRef());
        VectorScope scope = new VectorScope(scopeDefinition.userId(), scopeDefinition.knowledgeBaseId());
        VectorFilter filter = raw.documentSlot() == null
                ? VectorFilter.forKnowledgeBase(scope.userId(), scope.knowledgeBaseId())
                : VectorFilter.forDocument(
                        scope.userId(), scope.knowledgeBaseId(), documentId(scopeDefinition.id(), raw.documentSlot()));
        List<String> expectedIds = raw.expectedOrdinals().stream()
                .map(ordinal -> recordId(scopeDefinition.id(), ordinal))
                .toList();
        return new QueryCase(
                raw.id(),
                raw.scopeRef(),
                raw.semanticKey(),
                new VectorQuery(vectorFor(raw.semanticKey()), raw.topK(), filter, embedding),
                expectedIds,
                raw.noAnswer());
    }

    private DeletionCase expandDeletion(DeletionDefinition raw) {
        ScopeDefinition scopeDefinition = requireScope(raw.scopeRef());
        VectorScope scope = new VectorScope(scopeDefinition.userId(), scopeDefinition.knowledgeBaseId());
        List<String> expectedIds = raw.expectedOrdinals().stream()
                .map(ordinal -> recordId(scopeDefinition.id(), ordinal))
                .toList();
        String documentId = raw.documentSlot() == null
                ? null
                : documentId(scopeDefinition.id(), raw.documentSlot());
        return new DeletionCase(raw.id(), raw.mode(), scope, documentId, expectedIds);
    }

    private ScopeDefinition requireScope(String scopeRef) {
        ScopeDefinition scope = scopesByRef.get(scopeRef);
        if (scope == null) {
            throw new IllegalArgumentException("Unknown scope reference: " + scopeRef);
        }
        return scope;
    }

    public SegmentTemplate requireTemplate(int ordinal) {
        SegmentTemplate template = templatesByOrdinal.get(ordinal);
        if (template == null) {
            throw new IllegalArgumentException("Unknown segment ordinal: " + ordinal);
        }
        return template;
    }

    private static String recordId(String scopeRef, int ordinal) {
        return String.format("qv04-%s-segment-%02d", scopeRef, ordinal);
    }

    private static String documentId(String scopeRef, int documentSlot) {
        return String.format("qv04-%s-document-%02d", scopeRef, documentSlot);
    }

    public record FixtureDefinition(
            String fixtureId,
            boolean synthetic,
            boolean remoteEmbedding,
            EmbeddingDefinition embedding,
            GeneratorDefinition generator,
            List<ScopeDefinition> scopes,
            List<SegmentTemplate> segmentTemplates,
            List<QueryDefinition> queries,
            List<DeletionDefinition> deletions) {
    }

    public record EmbeddingDefinition(String model, int dimension, int schemaVersion) {
    }

    public record GeneratorDefinition(String algorithm, String seed) {
    }

    public record ScopeDefinition(String id, String userId, String knowledgeBaseId) {
    }

    public record SegmentTemplate(int ordinal, int documentSlot, String semanticKey, String text) {
    }

    public record QueryDefinition(
            String id,
            String scopeRef,
            String semanticKey,
            int topK,
            Integer documentSlot,
            List<Integer> expectedOrdinals,
            boolean noAnswer) {
    }

    public record DeletionDefinition(
            String id,
            String scopeRef,
            DeleteMode mode,
            Integer documentSlot,
            List<Integer> expectedOrdinals) {
    }

    public enum DeleteMode {
        ID,
        DOCUMENT
    }

    public record ExpandedRecord(
            VectorRecord record,
            String scopeRef,
            int ordinal,
            int documentSlot,
            String semanticKey,
            String syntheticText) {
    }

    public record QueryCase(
            String id,
            String scopeRef,
            String semanticKey,
            VectorQuery query,
            List<String> expectedIds,
            boolean noAnswer) {
    }

    public record DeletionCase(
            String id,
            DeleteMode mode,
            VectorScope scope,
            String documentId,
            List<String> expectedIds) {
    }
}
