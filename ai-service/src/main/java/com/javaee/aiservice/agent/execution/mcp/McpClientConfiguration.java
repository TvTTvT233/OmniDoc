package com.javaee.aiservice.agent.execution.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolGateway;
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
}
