package com.javaee.aiservice.review.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewM0IntegrationSelectorContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void selectorInstanceObeysPendingOrReadyAdapterBoundaryWithoutGuessedNames() throws Exception {
        JsonNode selector = selector();
        assertThat(selector.path("state").asText()).isIn("PENDING_INTEGRATION", "READY");
        if ("PENDING_INTEGRATION".equals(selector.path("state").asText())) {
            assertThat(selector.path("adapterId").isNull()).isTrue();
            assertThat(selector.path("testClasses")).isEmpty();
            assertThat(selector.path("targetSelectors")).isEmpty();
            assertThat(selector.path("staticScanRoots")).isEmpty();
            assertThat(selector.path("pendingReasons").size()).isGreaterThanOrEqualTo(3);
            return;
        }

        assertThat(selector.path("adapterId").asText()).isNotBlank();
        assertThat(selector.path("testClasses")).isNotEmpty();
        assertThat(selector.path("staticScanRoots")).isNotEmpty();
        assertThat(selector.path("pendingReasons")).isEmpty();
        List<String> targets = new ArrayList<>();
        selector.path("targetSelectors").fieldNames().forEachRemaining(targets::add);
        targets.sort(String::compareTo);
        assertThat(targets).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(value -> "M0-P0-%03d".formatted(value))
                .toList());
    }

    @Test
    void selectorSchemaMakesReadyStateAnExplicitAdapterBoundary() throws Exception {
        JsonNode schema = ReviewM0ReportSchemaTest.resource("review-m0-integration-selector.schema.json");
        JsonNode ready = schema.path("allOf").path(1).path("then").path("properties");
        assertThat(ready.path("adapterId").path("type").asText()).isEqualTo("string");
        assertThat(ready.path("testClasses").path("minItems").asInt()).isOne();
        assertThat(ready.path("targetSelectors").path("minProperties").asInt()).isEqualTo(15);
        assertThat(ready.path("targetSelectors").path("maxProperties").asInt()).isEqualTo(15);
        assertThat(ready.path("staticScanRoots").path("minItems").asInt()).isOne();
        assertThat(ready.path("pendingReasons").path("maxItems").asInt()).isZero();
    }

    @Test
    void selectorSchemaRequiresExactTestMethodsAndRelativeStaticRoots() throws Exception {
        JsonNode schema = ReviewM0ReportSchemaTest.resource("review-m0-integration-selector.schema.json");
        JsonNode target = schema.path("$defs").path("targetSelector");
        assertThat(ReviewM0ReportSchemaTest.textValues(target.path("required")))
                .containsExactly("className", "methodName");
        assertThat(target.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").path("staticScanRoots").path("items").path("pattern").asText())
                .contains("\\.\\.");
        assertThat(schema.path("properties").path("evidenceSystemProperty").path("const").asText())
                .isEqualTo("review.m0.acceptance.evidence.path");
    }

    private static JsonNode selector() throws Exception {
        try (InputStream input = ReviewM0IntegrationSelectorContractTest.class.getResourceAsStream(
                "/review/m0/acceptance/review-m0-integration-selector.json")) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
