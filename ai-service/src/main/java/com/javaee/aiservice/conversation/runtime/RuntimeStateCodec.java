package com.javaee.aiservice.conversation.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Uses a strict, versioned string envelope rather than accepting serializer-produced Maps.
 */
final class RuntimeStateCodec {

    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> ENVELOPE_FIELDS = Set.of("schemaVersion", "field", "value");

    private final ObjectMapper objectMapper;

    RuntimeStateCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String encode(RuntimeStateField field, Object value) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("schemaVersion", SCHEMA_VERSION);
            envelope.put("field", field.wireName());
            envelope.set("value", objectMapper.valueToTree(value));
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeStateException.Invalid("Unable to encode " + field.wireName(), e);
        }
    }

    Object decode(RuntimeStateField field, Object stored) {
        if (!(stored instanceof String json)) {
            throw new RuntimeStateException.Invalid("Stored " + field.wireName() + " is not a controlled envelope");
        }
        try {
            JsonNode envelope = objectMapper.readTree(json);
            if (envelope == null || !envelope.isObject() || !hasExactEnvelopeFields(envelope)) {
                throw new RuntimeStateException.Invalid("Stored " + field.wireName() + " has an invalid envelope");
            }
            if (envelope.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                    || !field.wireName().equals(envelope.path("field").asText())) {
                throw new RuntimeStateException.Invalid("Stored " + field.wireName() + " has an invalid schema or field");
            }
            JsonNode value = envelope.get("value");
            return switch (field) {
                case CURRENT_DOCUMENT_ID, SELECTED_TEXT, PAGE_CONTEXT -> requireText(field, value);
                case PENDING_APPROVAL -> objectMapper.treeToValue(
                        value,
                        RuntimeStateUpdate.PendingApprovalReference.class
                );
                case CURRENT_TOOL_STATE -> objectMapper.treeToValue(
                        value,
                        RuntimeStateUpdate.CurrentToolState.class
                );
            };
        } catch (RuntimeStateException.Invalid e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeStateException.Invalid("Unable to decode " + field.wireName(), e);
        }
    }

    private static String requireText(RuntimeStateField field, JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new RuntimeStateException.Invalid("Stored " + field.wireName() + " is not text");
        }
        return value.textValue();
    }

    private static boolean hasExactEnvelopeFields(JsonNode envelope) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = envelope.fieldNames();
        names.forEachRemaining(actual::add);
        return actual.equals(ENVELOPE_FIELDS);
    }
}
