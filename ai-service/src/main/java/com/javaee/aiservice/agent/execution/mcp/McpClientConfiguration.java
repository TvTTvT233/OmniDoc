package com.javaee.aiservice.agent.execution.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolGateway;
import com.javaee.aiservice.agent.execution.tool.McpToolCatalogAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpClientConfiguration {

    @Bean
    McpSyncClientFactory mcpSyncClientFactory() {
        return new SdkMcpSyncClientFactory();
    }

    @Bean
    McpToolGateway mcpToolGateway(
            McpProperties properties,
            McpSyncClientFactory clientFactory,
            ObjectMapper objectMapper
    ) {
        return new SdkMcpToolGateway(properties, clientFactory, objectMapper);
    }

    @Bean
    McpToolCatalogLifecycle mcpToolCatalogLifecycle(
            McpProperties properties,
            McpToolGateway gateway,
            ObjectProvider<McpToolCatalogAdapter> catalogAdapterProvider
    ) {
        return new McpToolCatalogLifecycle(properties, gateway, catalogAdapterProvider::getIfAvailable);
    }
}
