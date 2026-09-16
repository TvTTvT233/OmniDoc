package com.javaee.aiservice.agent.execution.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkMcpSyncClientFactoryTest {

    private final SdkMcpSyncClientFactory factory = new SdkMcpSyncClientFactory();

    @Test
    void rejectsCredentialsOrOtherValuesInUrlQuery() {
        McpProperties.Server properties = validProperties();
        String queryName = "api" + "-key";
        String testCredential = "not" + "-a-real-key";
        properties.setBaseUrl("https://example.com?" + queryName + "=" + testCredential);

        assertThatThrownBy(() -> factory.create("web-search", properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain user info, query parameters or fragments")
                .hasMessageNotContaining(testCredential);
    }

    @Test
    void rejectsUnsupportedTransport() {
        McpProperties.Server properties = validProperties();
        properties.setTransport("sse");

        assertThatThrownBy(() -> factory.create("web-search", properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only streamable-http");
    }

    private McpProperties.Server validProperties() {
        McpProperties.Server properties = new McpProperties.Server();
        properties.setBaseUrl("https://example.com");
        properties.setEndpoint("/mcp/");
        properties.setApiKey("not-a-real-key");
        properties.setNamespace("mcp.web-search");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
