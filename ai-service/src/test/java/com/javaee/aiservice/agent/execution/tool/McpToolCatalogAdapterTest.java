package com.javaee.aiservice.agent.execution.tool;

import com.javaee.aiservice.agent.execution.mcp.contract.McpToolDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolCatalogAdapterTest {

    private AgentToolRegistry registry;
    private McpToolCatalogAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new AgentToolRegistry();
        adapter = new McpToolCatalogAdapter(registry);
    }

    @Test
    void mapsNamespacedToolSchemaMetadataAndLocalReadOnlyPolicy() {
        Map<String, Object> querySchema = new LinkedHashMap<>();
        querySchema.put("type", "string");
        querySchema.put("description", "Web search query");
        querySchema.put("pattern", "^\\S.*$");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", querySchema);
        properties.put("max_results", Map.of(
                "type", "integer", "description", "Maximum result count", "minimum", 1, "maximum", 20));
        properties.put("score_threshold", Map.of(
                "type", "number", "minimum", 0.0, "maximum", 1.0));
        properties.put("include_raw", Map.of("type", "boolean"));
        properties.put("domains", Map.of(
                "type", "array", "items", Map.of("type", "string")));
        properties.put("filters", Map.of(
                "type", "object", "properties", Map.of("language", Map.of("type", "string"))));
        properties.put("search_depth", Map.of(
                "type", "string", "enum", List.of("basic", "advanced"), "default", "basic"));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("query"));

        McpToolDescriptor descriptor = descriptor("web-search", "tavily-search",
                "mcp.web-search.tavily-search", "Search the web", inputSchema);
        adapter.refreshTools("web-search", "mcp.web-search", Set.of("tavily-search"), List.of(descriptor));

        AgentToolDefinition tool = registry.get("mcp.web-search.tavily-search");
        assertThat(tool).isNotNull();
        assertThat(tool.getCategory()).isEqualTo("mcp-search");
        assertThat(tool.isDestructive()).isFalse();
        assertThat(tool.isRequiresUserAction()).isFalse();
        assertThat(tool.getRiskLevel()).isEqualTo("low");
        assertThat(tool.isReadOnly()).isTrue();
        assertThat(tool.getSourceMetadata()).containsEntry("source", "mcp")
                .containsEntry("serverId", "web-search")
                .containsEntry("remoteName", "tavily-search")
                .containsEntry("exposedName", "mcp.web-search.tavily-search");
        assertThat(tool.getSourceMetadata().get("annotations"))
                .isEqualTo(Map.of("readOnlyHint", false, "destructiveHint", true));

        assertThat(tool.getParameterSchema().get("query").isRequired()).isTrue();
        assertThat(tool.getParameterSchema().get("query").getPattern()).isEqualTo("^\\S.*$");
        assertThat(tool.getParameterSchema().get("max_results").getType()).isEqualTo("integer");
        assertThat(tool.getParameterSchema().get("max_results").getMinValue()).isEqualTo(1);
        assertThat(tool.getParameterSchema().get("max_results").getMaxValue()).isEqualTo(20);
        assertThat(tool.getParameterSchema().get("score_threshold").getType()).isEqualTo("number");
        assertThat(tool.getParameterSchema().get("include_raw").getType()).isEqualTo("boolean");
        assertThat(tool.getParameterSchema().get("domains").getType()).isEqualTo("array");
        assertThat(tool.getParameterSchema().get("filters").getType()).isEqualTo("object");
        assertThat(tool.getParameterSchema().get("search_depth").getAllowedValues())
                .containsExactly("basic", "advanced");
        assertThat(tool.getParameters().get("domains")).contains("type=array<string>");
        assertThat(tool.getInputSchema()).isEqualTo(inputSchema);

        querySchema.put("pattern", "changed-after-registration");
        assertThat(((Map<?, ?>) ((Map<?, ?>) tool.getInputSchema().get("properties")).get("query")).get("pattern"))
                .isEqualTo("^\\S.*$");
        assertThatThrownBy(() -> tool.getInputSchema().put("changed", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void filtersToolsOutsideTheLocalAllowlist() {
        McpToolDescriptor allowed = descriptor("web-search", "tavily-search",
                "mcp.web-search.tavily-search", "allowed", simpleSchema());
        McpToolDescriptor forbidden = descriptor("web-search", "tavily-extract",
                "mcp.web-search.tavily-extract", "forbidden", simpleSchema());

        List<AgentToolDefinition> registered = adapter.refreshTools(
                "web-search", Set.of("tavily-search"), List.of(allowed, forbidden));

        assertThat(registered).extracting(AgentToolDefinition::getName)
                .containsExactly("mcp.web-search.tavily-search");
        assertThat(registry.contains("mcp.web-search.tavily-extract")).isFalse();
    }

    @Test
    void refreshReplacesServerSnapshotWithoutDuplicates() {
        adapter.refreshTools("web-search", Set.of("tavily-search"), List.of(descriptor(
                "web-search", "tavily-search", "mcp.web-search.tavily-search", "version one", simpleSchema())));

        adapter.refreshTools("web-search", Set.of("tavily-search"), List.of(descriptor(
                "web-search", "tavily-search", "mcp.web-search.tavily-search", "version two", simpleSchema())));

        assertThat(registry.listMcpTools("web-search")).hasSize(1);
        assertThat(registry.get("mcp.web-search.tavily-search").getDescription()).isEqualTo("version two");
    }

    @Test
    void failedRefreshKeepsThePreviousServerSnapshot() {
        adapter.refreshTools("web-search", Set.of("tavily-search"), List.of(descriptor(
                "web-search", "tavily-search", "mcp.web-search.tavily-search", "stable", simpleSchema())));
        Map<String, Object> invalidSchema = new LinkedHashMap<>();
        invalidSchema.put("type", "object");
        invalidSchema.put("properties", Map.of("query", Map.of("type", "unsupported")));

        assertThatThrownBy(() -> adapter.refreshTools("web-search", Set.of("tavily-search"), List.of(descriptor(
                "web-search", "tavily-search", "mcp.web-search.tavily-search", "broken", invalidSchema))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");

        assertThat(registry.listMcpTools("web-search")).hasSize(1);
        assertThat(registry.get("mcp.web-search.tavily-search").getDescription()).isEqualTo("stable");
    }

    @Test
    void rejectsNamespaceMismatchAndCannotOverwriteNativeTool() {
        assertThatThrownBy(() -> adapter.refreshTools("web-search", Set.of("rag-search"), List.of(descriptor(
                "web-search", "rag-search", "rag-search", "collision", simpleSchema()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exposed tool name");

        assertThat(registry.get("rag-search").getCategory()).isEqualTo("rag");
        assertThat(registry.listMcpTools("web-search")).isEmpty();

        assertThatThrownBy(() -> registry.replaceMcpTools("web-search", List.of(
                mcpDefinition("rag-search", "web-search"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("native tool");
        assertThat(registry.get("rag-search").getCategory()).isEqualTo("rag");
    }

    @Test
    void duplicateCandidateNamesFailWithoutChangingPreviousSnapshot() {
        adapter.refreshTools("web-search", Set.of("tavily-search"), List.of(descriptor(
                "web-search", "tavily-search", "mcp.web-search.tavily-search", "stable", simpleSchema())));

        assertThatThrownBy(() -> adapter.refreshTools("web-search", Set.of("tavily-search"), List.of(
                descriptor("web-search", "tavily-search", "mcp.web-search.tavily-search", "duplicate one", simpleSchema()),
                descriptor("web-search", "tavily-search", "mcp.web-search.tavily-search", "duplicate two", simpleSchema()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");

        assertThat(registry.listMcpTools("web-search")).hasSize(1);
        assertThat(registry.get("mcp.web-search.tavily-search").getDescription()).isEqualTo("stable");
    }

    @Test
    void removingOneServerLeavesNativeAndOtherServerToolsUntouched() {
        List<AgentToolDefinition> nativeSnapshot = new ArrayList<>(registry.list());
        adapter.refreshTools("web-search", Set.of("tavily-search"), List.of(descriptor(
                "web-search", "tavily-search", "mcp.web-search.tavily-search", "web", simpleSchema())));
        adapter.refreshTools("academic-search", Set.of("paper-search"), List.of(descriptor(
                "academic-search", "paper-search", "mcp.academic-search.paper-search", "papers", simpleSchema())));

        assertThat(adapter.removeServerTools("web-search")).isEqualTo(1);

        assertThat(registry.contains("mcp.web-search.tavily-search")).isFalse();
        assertThat(registry.contains("mcp.academic-search.paper-search")).isTrue();
        assertThat(registry.list()).containsAll(nativeSnapshot);
        assertThat(registry.get("direct-answer")).isSameAs(nativeSnapshot.get(0));
    }

    private McpToolDescriptor descriptor(String serverId, String remoteName, String exposedName,
                                         String description, Map<String, Object> schema) {
        return new McpToolDescriptor(serverId, remoteName, exposedName, description, schema,
                Map.of("readOnlyHint", false, "destructiveHint", true));
    }

    private Map<String, Object> simpleSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string", "description", "Search query")),
                "required", List.of("query")
        );
    }

    private AgentToolDefinition mcpDefinition(String name, String serverId) {
        return new AgentToolDefinition(
                name, "collision", Map.of(), Map.of(), false, "mcp-search", false, "low", true,
                Map.of("type", "object"),
                Map.of("source", "mcp", "serverId", serverId, "remoteName", name, "exposedName", name)
        );
    }
}
