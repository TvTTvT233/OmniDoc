package com.javaee.aiservice.memory.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryV1SummarySchemaTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void schemaRequiresEveryFrozenSummarySectionAndRejectsUnknownTopLevelFields() throws Exception {
        JsonNode schema = load("/memory/memory-v1-summary-schema.json");

        assertThat(iterableText(schema.path("required"))).containsExactlyInAnyOrder(
                "schemaVersion", "conversationId", "summaryVersion", "coverage", "goal",
                "constraints", "facts", "decisions", "openQuestions", "todos", "resources", "meta");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").path("schemaVersion").path("const").asText())
                .isEqualTo("1.0");
    }

    @Test
    void schemaFreezesStatusAndSourceReferenceEnums() throws Exception {
        JsonNode defs = load("/memory/memory-v1-summary-schema.json").path("$defs");
        Map<String, List<String>> expected = Map.of(
                "goalItem", List.of("active", "completed", "cancelled"),
                "constraintItem", List.of("active", "superseded"),
                "factItem", List.of("confirmed", "uncertain", "conflicting", "superseded"),
                "decisionItem", List.of("proposed", "confirmed", "rejected", "superseded"),
                "openQuestionItem", List.of("open", "resolved", "dropped"),
                "todoItem", List.of("pending", "in_progress", "done", "cancelled"),
                "resourceItem", List.of("active", "superseded", "removed"));

        expected.forEach((definition, statuses) ->
                assertThat(statusEnum(defs.path(definition))).containsExactlyElementsOf(statuses));
        assertThat(iterableText(defs.path("sourceRef").path("properties").path("type").path("enum")))
                .containsExactly("MESSAGE", "DOCUMENT", "TOOL_RESULT");
    }

    @Test
    void deterministicSummaryUsesOnlySchemaStatusesAndCoveredSources() throws Exception {
        JsonNode summary = load("/memory/memory-v1-fixture.json").path("expectedSummary");
        JsonNode defs = load("/memory/memory-v1-summary-schema.json").path("$defs");
        int toSeq = summary.path("coverage").path("toSeq").asInt();

        assertItem(summary.path("goal"), statusEnum(defs.path("goalItem")), toSeq);
        assertItems(summary.path("constraints"), statusEnum(defs.path("constraintItem")), toSeq);
        assertItems(summary.path("facts"), statusEnum(defs.path("factItem")), toSeq);
        assertItems(summary.path("decisions"), statusEnum(defs.path("decisionItem")), toSeq);
        assertItems(summary.path("openQuestions"), statusEnum(defs.path("openQuestionItem")), toSeq);
        assertItems(summary.path("todos"), statusEnum(defs.path("todoItem")), toSeq);
        assertItems(summary.path("resources"), statusEnum(defs.path("resourceItem")), toSeq);
    }

    @Test
    void schemaDoesNotDefineRuntimeOrModelConfidenceAsSummaryFields() throws Exception {
        JsonNode properties = load("/memory/memory-v1-summary-schema.json").path("properties");

        assertThat(iterableFieldNames(properties)).doesNotContain(
                "selectedText", "pageContext", "pendingApproval", "currentToolState", "confidence");
        assertThat(iterableFieldNames(load("/memory/memory-v1-summary-schema.json")
                .path("$defs").path("itemBase").path("properties")))
                .doesNotContain("confidence");
    }

    @Test
    void schemaAllowsNullGoalInsteadOfForcingAnInventedConversationGoal() throws Exception {
        JsonNode goalAlternatives = load("/memory/memory-v1-summary-schema.json")
                .path("properties").path("goal").path("oneOf");

        assertThat(goalAlternatives.isArray()).isTrue();
        assertThat(goalAlternatives).anySatisfy(option ->
                assertThat(option.path("$ref").asText()).isEqualTo("#/$defs/goalItem"));
        assertThat(goalAlternatives).anySatisfy(option ->
                assertThat(option.path("type").asText()).isEqualTo("null"));
    }

    private static void assertItems(JsonNode items, List<String> statuses, int toSeq) {
        items.forEach(item -> assertItem(item, statuses, toSeq));
    }

    private static void assertItem(JsonNode item, List<String> statuses, int toSeq) {
        assertThat(item.path("id").asText()).isNotBlank();
        assertThat(item.path("content").asText()).isNotBlank();
        assertThat(item.path("status").asText()).isIn(statuses);
        assertThat(item.path("sourceRefs").isArray()).isTrue();
        assertThat(item.path("sourceRefs").size()).isPositive();
        assertThat(item.path("lastUpdatedSeq").asInt()).isBetween(1, toSeq);
    }

    private static List<String> statusEnum(JsonNode definition) {
        JsonNode direct = definition.path("properties").path("status").path("enum");
        if (direct.isArray()) {
            return iterableText(direct);
        }
        for (JsonNode part : definition.path("allOf")) {
            JsonNode values = part.path("properties").path("status").path("enum");
            if (values.isArray()) {
                return iterableText(values);
            }
        }
        return List.of();
    }

    private static JsonNode load(String resource) throws Exception {
        try (InputStream input = MemoryV1SummarySchemaTest.class.getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static List<String> iterableText(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.elements().forEachRemaining(item -> values.add(item.asText()));
        return values;
    }

    private static List<String> iterableFieldNames(JsonNode object) {
        List<String> values = new ArrayList<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }
}
