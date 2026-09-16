package com.javaee.aiservice.agent.execution.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.mcp.acceptance.MockMcpServer;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolCallResult;
import com.javaee.aiservice.agent.execution.tool.AgentToolDefinition;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.agent.execution.tool.McpToolCatalogAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpSearchIntegratedAcceptanceTest {

    private static final String SERVER_ID = "web-search";
    private static final String EXPOSED_TOOL = "mcp.web-search.tavily-search";

    @Test
    void realTransportDiscoveryPublishesSafeToolAndCallsFixture() throws Exception {
        String testToken = "fixture" + "-integration-token";
        try (MockMcpServer server = MockMcpServer.start(testToken)) {
            McpProperties properties = enabledProperties(server, testToken);
            SdkMcpToolGateway gateway = new SdkMcpToolGateway(
                    properties, new SdkMcpSyncClientFactory(), new ObjectMapper());
            AgentToolRegistry registry = new AgentToolRegistry();
            McpToolCatalogLifecycle catalogLifecycle = new McpToolCatalogLifecycle(
                    properties, gateway, new McpToolCatalogAdapter(registry));

            try {
                gateway.start();
                catalogLifecycle.start();

                assertThat(gateway.supports(EXPOSED_TOOL)).isTrue();
                assertThat(registry.contains(EXPOSED_TOOL)).isTrue();
                assertThat(registry.contains("mcp.web-search.tavily-extract")).isFalse();
                assertThat(registry.contains("mcp.web-search.tavily-crawl")).isFalse();

                AgentToolDefinition definition = registry.get(EXPOSED_TOOL);
                assertThat(definition.getCategory()).isEqualTo("mcp-search");
                assertThat(definition.isReadOnly()).isTrue();
                assertThat(definition.isDestructive()).isFalse();
                assertThat(definition.getRiskLevel()).isEqualTo("low");
                assertThat(definition.getParameterSchema().get("query").isRequired()).isTrue();
                assertThat(definition.getParameterSchema().get("max_results").getType()).isEqualTo("integer");

                McpToolCallResult result = gateway.callTool(
                        EXPOSED_TOOL, Map.of("query", "fixture:success"));

                assertThat(result.error()).isFalse();
                assertThat(result.structuredContent()).containsKey("results");
                assertThat(result.serverId()).isEqualTo(SERVER_ID);
                assertThat(result.remoteToolName()).isEqualTo("tavily-search");
            } finally {
                catalogLifecycle.stop();
                gateway.stop();
            }

            assertThat(registry.contains(EXPOSED_TOOL)).isFalse();
            assertThat(registry.contains("direct-answer")).isTrue();
        }
    }

    private McpProperties enabledProperties(MockMcpServer server, String testToken) {
        McpProperties.Server configuredServer = new McpProperties.Server();
        configuredServer.setEnabled(true);
        configuredServer.setTransport("streamable-http");
        configuredServer.setBaseUrl(server.endpoint().resolve("/").toString());
        configuredServer.setEndpoint(server.endpoint().getPath());
        configuredServer.setApiKey(testToken);
        configuredServer.setNamespace("mcp.web-search");
        configuredServer.setConnectTimeout(Duration.ofSeconds(2));
        configuredServer.setRequestTimeout(Duration.ofSeconds(2));
        configuredServer.setMaxResultChars(30_000);
        configuredServer.setAllowedTools(List.of("tavily-search"));

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setFailFast(true);
        properties.setServers(Map.of(SERVER_ID, configuredServer));
        return properties;
    }
}
