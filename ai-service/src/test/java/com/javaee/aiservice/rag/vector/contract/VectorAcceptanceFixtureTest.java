package com.javaee.aiservice.rag.vector.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VectorAcceptanceFixtureTest {

    @Test
    void fixtureCoversThreeUsersTwoKnowledgeBasesAndCrossTenantSimilarity() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> fixture;
        try (InputStream input = getClass().getResourceAsStream("/qdrant/qv00-vector-fixture.json")) {
            assertThat(input).isNotNull();
            fixture = objectMapper.readValue(input, new TypeReference<>() { });
        }

        Map<String, Object> rawEmbedding = castMap(fixture.get("embedding"));
        EmbeddingSpec embedding = new EmbeddingSpec(
                text(rawEmbedding.get("model")),
                number(rawEmbedding.get("dimension")),
                number(rawEmbedding.get("schemaVersion")));

        List<VectorRecord> records = new ArrayList<>();
        Map<String, VectorRecord> recordsById = new HashMap<>();
        for (Object raw : castList(fixture.get("records"))) {
            Map<String, Object> item = castMap(raw);
            VectorRecord record = new VectorRecord(
                    text(item.get("id")),
                    text(item.get("documentId")),
                    vector(item.get("vector")),
                    new VectorScope(text(item.get("userId")), text(item.get("knowledgeBaseId"))),
                    embedding,
                    castMap(item.get("metadata")));
            records.add(record);
            recordsById.put(record.id(), record);
        }

        assertThat(records).hasSize(12);
        assertThat(records.stream().map(VectorRecord::scope).map(VectorScope::userId).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("user-a", "user-b", "user-c");
        assertThat(records.stream().map(VectorRecord::scope).map(VectorScope::knowledgeBaseId).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("kb-a", "kb-b");
        List<List<Float>> vectors = records.stream()
                .map(record -> List.of(record.vector()[0], record.vector()[1], record.vector()[2]))
                .toList();
        assertThat(new HashSet<>(vectors).size()).isLessThan(vectors.size());

        Set<String> queryScopes = new HashSet<>();
        List<?> queries = castList(fixture.get("queries"));
        assertThat(queries).hasSize(6);
        for (Object raw : queries) {
            Map<String, Object> item = castMap(raw);
            VectorScope scope = new VectorScope(text(item.get("userId")), text(item.get("knowledgeBaseId")));
            VectorQuery query = new VectorQuery(
                    vector(item.get("vector")),
                    number(item.get("topK")),
                    VectorFilter.forKnowledgeBase(scope.userId(), scope.knowledgeBaseId()),
                    embedding);
            List<String> expectedIds = castList(item.get("expectedIds")).stream().map(VectorAcceptanceFixtureTest::text).toList();
            assertThat(expectedIds).hasSize(query.topK());
            assertThat(expectedIds).allSatisfy(expectedId ->
                    assertThat(recordsById.get(expectedId).scope()).isEqualTo(scope));
            queryScopes.add(scope.userId() + ":" + scope.knowledgeBaseId());
        }
        assertThat(queryScopes).hasSize(6);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    private static List<?> castList(Object value) {
        return (List<?>) value;
    }

    private static String text(Object value) {
        return String.valueOf(value);
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static float[] vector(Object value) {
        List<?> values = castList(value);
        float[] vector = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            vector[index] = ((Number) values.get(index)).floatValue();
        }
        return vector;
    }
}
