package com.javaee.aiservice.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.agent.execution.mcp.McpToolResultAdapter;
import com.javaee.aiservice.agent.execution.mcp.contract.McpErrorCode;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolCallResult;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolGateway;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.model.AgentPlanStep;
import com.javaee.aiservice.agent.execution.model.AgentToolResult;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.agent.execution.tool.AgentToolDefinition;
import com.javaee.aiservice.agent.execution.tool.AgentToolParameterDefinition;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.conversation.ContextManager;
import com.javaee.aiservice.conversation.ConversationManager;
import com.javaee.aiservice.internal.InternalService;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpAgentRoutingTest {

    private static final String MCP_TOOL = "mcp.web-search.tavily-search";

    @Test
    void fallbackRoutesExplicitRealtimeIntentToRegisteredMcpSearch() {
        AgentExecutionService service = new AgentExecutionService();
        ReflectionTestUtils.setField(service, "toolRegistry", registryWithMcp());
        AgentExecutionRequest request = request("查询当前最新的国家奖学金政策网页");

        List<AgentPlanStep> plan = ReflectionTestUtils.invokeMethod(
                service, "fallbackPlan", request, Map.of());

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).getToolName()).isEqualTo(MCP_TOOL);
        assertThat(plan.get(0).getParams()).containsEntry("query", request.getTask());
    }

    @Test
    void internalMaterialIntentReroutesModelSelectedMcpToRag() {
        AgentExecutionService service = new AgentExecutionService();
        ReflectionTestUtils.setField(service, "toolRegistry", registryWithMcp());
        AgentExecutionRequest request = request("根据已上传材料检查申请条件");
        AgentPlanStep step = new AgentPlanStep("search", "检索", MCP_TOOL,
                new HashMap<>(Map.of("query", request.getTask())));

        List<AgentPlanStep> plan = ReflectionTestUtils.invokeMethod(
                service, "normalizePlan", new ArrayList<>(List.of(step)), request, Map.of("uploadedFileIds", List.of("f-1")));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).getToolName()).isEqualTo("rag-answer");
        assertThat(plan.get(0).getParams()).containsEntry("question", request.getTask());
    }

    @Test
    void explicitNoNetworkIntentShieldsMcpAndUsesDirectAnswer() {
        AgentExecutionService service = new AgentExecutionService();
        ReflectionTestUtils.setField(service, "toolRegistry", registryWithMcp());
        AgentExecutionRequest request = request("不要联网，告诉我当前网页上的最新信息");
        AgentPlanStep step = new AgentPlanStep("search", "检索", MCP_TOOL,
                new HashMap<>(Map.of("query", request.getTask())));

        List<AgentPlanStep> plan = ReflectionTestUtils.invokeMethod(
                service, "normalizePlan", new ArrayList<>(List.of(step)), request, Map.of());

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).getToolName()).isEqualTo("direct-answer");
        assertThat(plan.get(0).getParams().get("question").toString()).contains("明确禁止联网");
    }

    @Test
    void mcpMissingRequiredParameterUsesExistingActionRequiredFlow() {
        McpToolGateway gateway = mock(McpToolGateway.class);
        when(gateway.supports(MCP_TOOL)).thenReturn(true);
        ChatService chatService = mock(ChatService.class);
        when(chatService.callChatApiWithModelCode(any(), any())).thenReturn("""
                [{"id":"search","description":"search","toolName":"mcp.web-search.tavily-search","params":{"query":""}}]
                """, "请补充查询词");
        AgentExecutionService service = executableService(chatService, gateway);

        Map<String, Object> response = service.execute(request("搜索外部资料"));

        assertThat(response.get("status")).isEqualTo("action_required");
        @SuppressWarnings("unchecked")
        List<AgentToolResult> results = (List<AgentToolResult>) response.get("toolResults");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo("action_required");
        assertThat(results.get(0).getData()).containsEntry("missingParameters", List.of("query"));
        assertThat(response).containsKey("pendingUserInput");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) response.get("timeline");
        @SuppressWarnings("unchecked")
        Map<String, Object> traceMcp = (Map<String, Object>) timeline.get(0).get("mcp");
        assertThat(traceMcp).containsEntry("serverId", "web-search")
                .containsEntry("remoteToolName", "tavily-search")
                .containsEntry("exposedToolName", MCP_TOOL)
                .containsEntry("status", "action_required");
        verify(gateway, never()).callTool(any(), any());
    }

    @Test
    void structuredContentWinsAndSourcesAreDeduplicatedInOriginalOrder() {
        McpToolResultAdapter adapter = new McpToolResultAdapter(new ObjectMapper());
        McpToolCallResult callResult = McpToolCallResult.success(
                "web-search", "tavily-search", MCP_TOOL,
                List.of(Map.of("type", "text", "text", "{\"results\":[{\"url\":\"https://ignored.example\"}]}")),
                Map.of("query", "q", "results", List.of(
                        Map.of("title", "A", "url", "https://a.example", "content", "first", "score", 0.9),
                        Map.of("title", "A2", "url", "https://a.example", "content", "duplicate"),
                        Map.of("title", "B", "url", "https://b.example", "snippet", "second"),
                        Map.of("title", "Unsafe", "url", "javascript:alert(1)"))),
                Map.of(), 21L);

        AgentToolResult result = adapter.adapt(callResult, Map.of("query", "ignored"), 10_000);

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData()).containsEntry("query", "q");
        assertThat(result.getData().get("sources")).isEqualTo(List.of("https://a.example", "https://b.example"));
        assertThat(result.getData().toString()).doesNotContain("ignored.example", "javascript:");
        @SuppressWarnings("unchecked")
        Map<String, Object> mcp = (Map<String, Object>) result.getData().get("mcp");
        assertThat(mcp)
                .containsEntry("serverId", "web-search")
                .containsEntry("remoteToolName", "tavily-search")
                .containsEntry("exposedToolName", MCP_TOOL)
                .containsEntry("durationMs", 21L);
    }

    @Test
    void textContentFallsBackToNormalizedResultsAndActualUrls() {
        McpToolResultAdapter adapter = new McpToolResultAdapter(new ObjectMapper());
        McpToolCallResult callResult = McpToolCallResult.success(
                "web-search", "tavily-search", MCP_TOOL,
                List.of(Map.of("type", "text", "text", "Result at https://one.example/page and https://two.example")),
                Map.of(), Map.of(), 5L);

        AgentToolResult result = adapter.adapt(callResult, Map.of("query", "fallback"), 10_000);

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData().get("sources"))
                .isEqualTo(List.of("https://one.example/page", "https://two.example"));
        assertThat((List<?>) result.getData().get("results")).hasSize(2);
    }

    @Test
    void oversizedMcpResultIsRejectedBeforeEnteringAgentContext() {
        McpToolResultAdapter adapter = new McpToolResultAdapter(new ObjectMapper());
        McpToolCallResult callResult = McpToolCallResult.success(
                "web-search", "tavily-search", MCP_TOOL,
                List.of(), Map.of("results", List.of(Map.of("content", "x".repeat(500)))), Map.of(), 3L);

        AgentToolResult result = adapter.adapt(callResult, Map.of("query", "large"), 100);

        assertThat(result.getStatus()).isEqualTo("error");
        assertThat(result.getData()).containsEntry("errorCode", "MCP_RESULT_TOO_LARGE")
                .containsEntry("results", List.of())
                .containsEntry("sources", List.of());
        assertThat(result.getData().toString()).doesNotContain("x".repeat(100));
    }

    @Test
    void successfulMcpCallFeedsFinalAnswerButRemovesInventedSourcesAndAddsTraceMetadata() {
        McpToolGateway gateway = mock(McpToolGateway.class);
        when(gateway.supports(MCP_TOOL)).thenReturn(true);
        when(gateway.callTool(eq(MCP_TOOL), any())).thenReturn(McpToolCallResult.success(
                "web-search", "tavily-search", MCP_TOOL, List.of(),
                Map.of("query", "current policy", "results", List.of(
                        Map.of("title", "Policy", "url", "https://actual.example/policy", "content", "updated"))),
                Map.of(), 44L));
        ChatService chatService = mock(ChatService.class);
        when(chatService.callChatApiWithModelCode(any(), any())).thenReturn(
                "[{\"id\":\"answer\",\"description\":\"answer\",\"toolName\":\"direct-answer\",\"params\":{}}]",
                "结论见 https://actual.example/policy，另见 https://invented.example/fake");
        AgentExecutionService service = executableService(chatService, gateway);

        Map<String, Object> response = service.execute(request("查询当前最新政策网页"));

        assertThat(response.get("status")).isEqualTo("success");
        assertThat(response.get("answer").toString())
                .contains("https://actual.example/policy")
                .doesNotContain("https://invented.example/fake");
        @SuppressWarnings("unchecked")
        List<AgentToolResult> results = (List<AgentToolResult>) response.get("toolResults");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getToolName()).isEqualTo(MCP_TOOL);
        assertThat(results.get(0).getData().get("sources")).isEqualTo(List.of("https://actual.example/policy"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) response.get("timeline");
        assertThat(timeline).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> traceMcp = (Map<String, Object>) timeline.get(0).get("mcp");
        assertThat(traceMcp)
                .containsEntry("serverId", "web-search")
                .containsEntry("remoteToolName", "tavily-search")
                .containsEntry("exposedToolName", MCP_TOOL)
                .containsEntry("status", "success")
                .containsEntry("durationMs", 44L);
        verify(gateway).callTool(eq(MCP_TOOL), any());
    }

    @Test
    void emptyMcpResultsProduceExplicitNoResultsAnswer() {
        McpToolGateway gateway = mock(McpToolGateway.class);
        when(gateway.supports(MCP_TOOL)).thenReturn(true);
        when(gateway.callTool(eq(MCP_TOOL), any())).thenReturn(McpToolCallResult.success(
                "web-search", "tavily-search", MCP_TOOL, List.of(),
                Map.of("query", "nothing", "results", List.of()), Map.of(), 7L));
        ChatService chatService = mock(ChatService.class);
        when(chatService.callChatApiWithModelCode(any(), any())).thenReturn(
                "[{\"id\":\"search\",\"description\":\"search\",\"toolName\":\"mcp.web-search.tavily-search\",\"params\":{\"query\":\"nothing\"}}]");
        AgentExecutionService service = executableService(chatService, gateway);

        Map<String, Object> response = service.execute(request("联网查询当前不存在的条目"));

        assertThat(response.get("status")).isEqualTo("success");
        assertThat(response.get("answer").toString()).contains("未找到").contains("外部搜索结果");
    }

    @Test
    void nonRetryableMcpFailureKeepsErrorCodeInTraceAndDoesNotClaimRealtimeData() {
        McpToolGateway gateway = mock(McpToolGateway.class);
        when(gateway.supports(MCP_TOOL)).thenReturn(true);
        when(gateway.callTool(eq(MCP_TOOL), any())).thenReturn(McpToolCallResult.failure(
                "web-search", "tavily-search", MCP_TOOL,
                McpErrorCode.MCP_AUTH_FAILED, "Authorization: Bearer secret-value", 12L));
        ChatService chatService = mock(ChatService.class);
        when(chatService.callChatApiWithModelCode(any(), any())).thenReturn(
                "[{\"id\":\"search\",\"description\":\"search\",\"toolName\":\"mcp.web-search.tavily-search\",\"params\":{\"query\":\"latest\"},\"retryPolicy\":\"exponential\",\"maxRetries\":2}]");
        AgentExecutionService service = executableService(chatService, gateway);

        Map<String, Object> response = service.execute(request("联网查询最新消息"));

        assertThat(response.get("status")).isEqualTo("error");
        assertThat(response.get("answer").toString()).contains("未能获取实时外部搜索结果")
                .contains("MCP_AUTH_FAILED")
                .contains("无法确认最新信息")
                .doesNotContain("secret-value");
        @SuppressWarnings("unchecked")
        List<AgentToolResult> results = (List<AgentToolResult>) response.get("toolResults");
        assertThat(results.get(0).getData()).containsEntry("errorCode", "MCP_AUTH_FAILED");
        assertThat(results.get(0).getMessage()).doesNotContain("secret-value");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) response.get("timeline");
        @SuppressWarnings("unchecked")
        Map<String, Object> traceMcp = (Map<String, Object>) timeline.get(0).get("mcp");
        assertThat(traceMcp)
                .containsEntry("status", "error")
                .containsEntry("errorCode", "MCP_AUTH_FAILED")
                .containsEntry("durationMs", 12L);
        verify(gateway, times(1)).callTool(eq(MCP_TOOL), any());
    }

    private AgentExecutionService executableService(ChatService chatService, McpToolGateway gateway) {
        AgentExecutionService service = new AgentExecutionService();
        ConversationManager conversationManager = mock(ConversationManager.class);
        ContextManager contextManager = mock(ContextManager.class);
        InternalService internalService = mock(InternalService.class);
        RequestUserContext requestUserContext = mock(RequestUserContext.class);

        when(requestUserContext.getRequiredUserId()).thenReturn("user-1");
        when(requestUserContext.getCurrentRole()).thenReturn("USER");
        when(conversationManager.createConversation("user-1")).thenReturn("conv-1");
        when(contextManager.getContext("conv-1")).thenReturn(new HashMap<>());
        when(internalService.hasPermission(any(), any())).thenReturn(true);
        doNothing().when(conversationManager).addMessageForUser(eq("conv-1"), eq("user-1"), any(), any());
        doNothing().when(contextManager).updateContext(eq("conv-1"), any());
        doNothing().when(internalService).logAudit(any(), any(), any());

        ReflectionTestUtils.setField(service, "chatService", chatService);
        ReflectionTestUtils.setField(service, "conversationManager", conversationManager);
        ReflectionTestUtils.setField(service, "contextManager", contextManager);
        ReflectionTestUtils.setField(service, "internalService", internalService);
        ReflectionTestUtils.setField(service, "toolRegistry", registryWithMcp());
        ReflectionTestUtils.setField(service, "requestUserContext", requestUserContext);
        ReflectionTestUtils.setField(service, "taskRegistry", new AgentTaskRegistry());
        ReflectionTestUtils.setField(service, "mcpToolGateway", gateway);
        ReflectionTestUtils.setField(service, "mcpMaxResultChars", 30_000);
        ReflectionTestUtils.setField(service, "defaultReflectionEnabled", false);
        return service;
    }

    private AgentExecutionRequest request(String task) {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setTask(task);
        request.setContext(Map.of());
        request.setReflectionEnabled(false);
        request.setAutoReplan(false);
        return request;
    }

    @SuppressWarnings("unchecked")
    private AgentToolRegistry registryWithMcp() {
        AgentToolRegistry registry = new AgentToolRegistry();
        Map<String, AgentToolDefinition> tools = (Map<String, AgentToolDefinition>) ReflectionTestUtils.getField(registry, "tools");
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("query", "Search query");
        Map<String, AgentToolParameterDefinition> schema = new LinkedHashMap<>();
        schema.put("query", new AgentToolParameterDefinition("query", "string", "Search query", true, null));
        tools.put(MCP_TOOL, new AgentToolDefinition(
                MCP_TOOL, "Search the current web", parameters, schema,
                false, "mcp-search", false, "low"));
        return registry;
    }
}
