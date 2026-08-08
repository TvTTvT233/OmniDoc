package com.javaee.aiservice.controller;

import com.javaee.aiservice.agent.execution.AgentExecutionService;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolDescriptor;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.agent.execution.tool.McpToolCatalogAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpToolCatalogEndpointTest {

    @Test
    void registeredMcpToolIsVisibleFromAgentToolsEndpoint() throws Exception {
        AgentToolRegistry registry = new AgentToolRegistry();
        McpToolCatalogAdapter adapter = new McpToolCatalogAdapter(registry);
        adapter.refreshTools("web-search", Set.of("tavily-search"), List.of(new McpToolDescriptor(
                "web-search",
                "tavily-search",
                "mcp.web-search.tavily-search",
                "Search the public web",
                Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string")),
                        "required", List.of("query")
                ),
                Map.of()
        )));

        AgentExecutionService executionService = new AgentExecutionService();
        ReflectionTestUtils.setField(executionService, "toolRegistry", registry);
        AgentController controller = new AgentController();
        ReflectionTestUtils.setField(controller, "agentExecutionService", executionService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/ai/agent/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == 'mcp.web-search.tavily-search')]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.name == 'mcp.web-search.tavily-search')].readOnly").value(true))
                .andExpect(jsonPath("$.data[?(@.name == 'mcp.web-search.tavily-search')].inputSchema.type")
                        .value("object"))
                .andExpect(jsonPath("$.data[?(@.name == 'mcp.web-search.tavily-search')].parameterSchema.query.required")
                        .value(true));
    }
}
