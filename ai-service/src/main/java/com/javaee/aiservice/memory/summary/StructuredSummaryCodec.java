package com.javaee.aiservice.memory.summary;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public final class StructuredSummaryCodec {

    private final ObjectMapper objectMapper;

    public StructuredSummaryCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public StructuredConversationSummary parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(stripJsonFence(raw));
            requireRequiredShape(root);
            return objectMapper.treeToValue(root, StructuredConversationSummary.class);
        } catch (Exception error) {
            throw new SummaryValidationException("Structured Summary JSON is invalid", error);
        }
    }

    private static void requireRequiredShape(JsonNode root) {
        java.util.Set<String> required = java.util.Set.of(
                "schemaVersion", "conversationId", "summaryVersion", "coverage", "goal",
                "constraints", "facts", "decisions", "openQuestions", "todos", "resources", "meta");
        if (root == null || !root.isObject()) {
            throw new SummaryValidationException("Structured Summary root must be an object");
        }
        for (String field : required) {
            if (!root.has(field)) {
                throw new SummaryValidationException("Structured Summary is missing a required field");
            }
        }
        for (String array : java.util.List.of(
                "constraints", "facts", "decisions", "openQuestions", "todos", "resources")) {
            if (!root.path(array).isArray()) {
                throw new SummaryValidationException("Structured Summary arrays must be present");
            }
        }
        JsonNode coverage = root.path("coverage");
        if (!coverage.isObject() || !coverage.has("fromSeq")
                || !coverage.has("toSeq") || !coverage.has("sourceDigest")) {
            throw new SummaryValidationException("Structured Summary coverage is incomplete");
        }
        JsonNode meta = root.path("meta");
        if (!meta.isObject() || !meta.has("previousSummaryVersion") || !meta.has("generatedAt")) {
            throw new SummaryValidationException("Structured Summary meta is incomplete");
        }
    }

    public String write(StructuredConversationSummary summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception error) {
            throw new SummaryValidationException("Structured Summary cannot be serialized", error);
        }
    }

    private static String stripJsonFence(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "");
            value = value.replaceFirst("\\s*```$", "");
        }
        return value;
    }
}
