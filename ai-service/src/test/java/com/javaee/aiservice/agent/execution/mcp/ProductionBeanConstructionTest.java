package com.javaee.aiservice.agent.execution.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolGateway;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.agent.execution.tool.McpToolCatalogAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionBeanConstructionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpClientConfiguration.class, TestConfiguration.class);

    @Test
    void catalogAdapterHasOneExplicitProductionInjectionConstructor() {
        List<Constructor<?>> injectionConstructors = Arrays.stream(
                        McpToolCatalogAdapter.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toList();

        assertThat(injectionConstructors).singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(AgentToolRegistry.class));
    }

    @Test
    void disabledMcpStartsContextWithoutConnectingToConfiguredServer() {
        contextRunner.withPropertyValues(
                        "agent.mcp.enabled=false",
                        "agent.mcp.fail-fast=true",
                        "agent.mcp.servers.web-search.enabled=true",
                        "agent.mcp.servers.web-search.transport=streamable-http",
                        "agent.mcp.servers.web-search.base-url=http://127.0.0.1:1",
                        "agent.mcp.servers.web-search.endpoint=/mcp/",
                        "agent.mcp.servers.web-search.api-key=not-a-real-key",
                        "agent.mcp.servers.web-search.namespace=mcp.web-search",
                        "agent.mcp.servers.web-search.connect-timeout=100ms",
                        "agent.mcp.servers.web-search.request-timeout=100ms",
                        "agent.mcp.servers.web-search.max-result-chars=30000",
                        "agent.mcp.servers.web-search.allowed-tools[0]=tavily-search"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(McpToolGateway.class);
                    assertThat(context).hasSingleBean(McpToolCatalogLifecycle.class);
                    assertThat(context.getBean(McpToolGateway.class).discoverTools()).isEmpty();
                    assertThat(context.getBean(AgentToolRegistry.class).listMcpTools("web-search")).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AgentToolRegistry agentToolRegistry() {
            return new AgentToolRegistry();
        }

        @Bean
        McpToolCatalogAdapter mcpToolCatalogAdapter(AgentToolRegistry registry) {
            return new McpToolCatalogAdapter(registry);
        }
    }
}
