package com.javaee.aiservice.rag.vector.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Qv04ReportSchemaTest {

    @Test
    void reportSchemaRequiresAllHardGatesAndPerformanceEntryPoints() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/qdrant/qv04-report-schema.json")) {
            assertThat(input).isNotNull();
            JsonNode schema = new ObjectMapper().readTree(input);
            assertThat(iterableText(schema.path("required")))
                    .contains("status", "fixture", "environment", "tests", "gates", "metrics", "unverified");
            JsonNode gateRequired = schema.path("properties").path("gates").path("required");
            assertThat(iterableText(gateRequired)).containsExactlyInAnyOrder(
                    "qdrantRecallAt5AtLeastBaseline",
                    "tenantLeakage",
                    "deleteResidue",
                    "migrationCountDifference");
            assertThat(iterableText(schema.path("properties").path("metrics").path("required")))
                    .contains("baseline", "qdrant", "shadowOverlapAtK");
        }
    }

    private static List<String> iterableText(JsonNode array) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        array.elements().forEachRemaining(item -> values.add(item.asText()));
        return values;
    }
}
