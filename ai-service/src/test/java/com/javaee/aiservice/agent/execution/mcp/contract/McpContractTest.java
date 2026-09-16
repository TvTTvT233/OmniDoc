package com.javaee.aiservice.agent.execution.mcp.contract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpContractTest {

    @Test
    void toolDescriptorRequiresStableIdentityFields() {
        assertThatThrownBy(() -> new McpToolDescriptor(
                " ", "tavily-search", "mcp.web-search.tavily-search", "search", Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serverId");
    }

    @Test
    void toolDescriptorDefensivelyCopiesSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("default", null);

        McpToolDescriptor descriptor = new McpToolDescriptor(
                "web-search",
                "tavily-search",
                "mcp.web-search.tavily-search",
                "search the web",
                schema,
                Map.of("readOnlyHint", true)
        );
        schema.put("changed", true);

        assertThat(descriptor.inputSchema()).containsOnlyKeys("type", "default");
        assertThat(descriptor.inputSchema()).containsEntry("default", null);
        assertThatThrownBy(() -> descriptor.inputSchema().put("another", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void callResultNormalizesCollectionsAndErrorDefaults() {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(new LinkedHashMap<>(Map.of("type", "text", "text", "result")));

        McpToolCallResult success = McpToolCallResult.success(
                "web-search",
                "tavily-search",
                "mcp.web-search.tavily-search",
                content,
                Map.of("results", List.of()),
                Map.of(),
                -1
        );
        content.clear();

        assertThat(success.error()).isFalse();
        assertThat(success.errorCode()).isEqualTo(McpErrorCode.NONE);
        assertThat(success.durationMs()).isZero();
        assertThat(success.content()).hasSize(1);

        McpToolCallResult failure = McpToolCallResult.failure(
                "web-search",
                "tavily-search",
                "mcp.web-search.tavily-search",
                McpErrorCode.MCP_TIMEOUT,
                "request timed out",
                20
        );

        assertThat(failure.error()).isTrue();
        assertThat(failure.errorCode()).isEqualTo(McpErrorCode.MCP_TIMEOUT);
        assertThat(failure.errorMessage()).isEqualTo("request timed out");
    }
}
