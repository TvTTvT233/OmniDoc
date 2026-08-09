package com.javaee.aiservice.agent.execution.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaee.aiservice.agent.execution.mcp.contract.McpErrorCode;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolCallResult;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolDescriptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SdkMcpToolGatewayIntegrationTest {

    private static final String API_KEY = "not-a-real-mcp-key";
    private static final String EXPOSED_TOOL = "mcp.web-search.tavily-search";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMcpServer mockServer;
    private SdkMcpToolGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockMcpServer(objectMapper);
        mockServer.start();
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.stop();
        }
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @Test
    void initializesPaginatesFiltersAndCallsToolWithoutLosingContent() {
        mockServer.setSessionEnabled(true);
        gateway = gateway(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(2), 30_000);

        gateway.start();

        assertThat(gateway.isRunning()).isTrue();
        assertThat(gateway.discoverTools()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.serverId()).isEqualTo("web-search");
            assertThat(descriptor.remoteName()).isEqualTo("tavily-search");
            assertThat(descriptor.exposedName()).isEqualTo(EXPOSED_TOOL);
            assertThat(descriptor.inputSchema()).containsEntry("type", "object");
            assertThat(descriptor.annotations()).containsEntry("readOnlyHint", true);
        });
        assertThat(gateway.supports(EXPOSED_TOOL)).isTrue();
        assertThat(gateway.supports("mcp.web-search.not-allowed")).isFalse();
        assertThat(mockServer.listCursors()).containsExactly("", "page-2");

        Map<String, Object> arguments = Map.of(
                "query", "latest scholarship rules",
                "options", Map.of("max_results", 3),
                "include_domains", List.of("example.edu")
        );
        McpToolCallResult result = gateway.callTool(EXPOSED_TOOL, arguments);

        assertThat(result.error()).isFalse();
        assertThat(result.errorCode()).isEqualTo(McpErrorCode.NONE);
        assertThat(result.serverId()).isEqualTo("web-search");
        assertThat(result.remoteToolName()).isEqualTo("tavily-search");
        assertThat(result.content()).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("type", "text")
                .containsEntry("text", "search result"));
        assertThat(result.structuredContent()).containsEntry("query", "latest scholarship rules");
        assertThat(result.metadata()).containsEntry("requestId", "request-1");
        assertThat(mockServer.lastCallArguments()).isEqualTo(objectMapper.valueToTree(arguments));
        assertThat(mockServer.authorizationHeaders()).isNotEmpty().allMatch(("Bearer " + API_KEY)::equals);

        gateway.stop();
        awaitDelete();
        assertThat(mockServer.methods()).contains("DELETE");
    }

    @Test
    void rejectsToolOutsideAllowlistBeforeRemoteCall() {
        gateway = gateway(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(2), 30_000);
        gateway.start();
        int callsBefore = mockServer.callCount();

        McpToolCallResult result = gateway.callTool("mcp.web-search.hidden-tool", Map.of("query", "x"));

        assertThat(result.errorCode()).isEqualTo(McpErrorCode.MCP_TOOL_NOT_ALLOWED);
        assertThat(mockServer.callCount()).isEqualTo(callsBefore);
    }

    @Test
    void mapsAuthenticationFailureAndFailsOpen() {
        mockServer.setUnauthorized(true);
        gateway = gateway(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(1), 30_000);

        gateway.start();

        assertThat(gateway.isRunning()).isTrue();
        assertThat(gateway.discoverTools()).isEmpty();
        assertThat(gateway.callTool(EXPOSED_TOOL, Map.of()).errorCode())
                .isEqualTo(McpErrorCode.MCP_AUTH_FAILED);
    }

    @Test
    void mapsRequestTimeoutAndFailsOpen() {
        mockServer.setResponseDelay(Duration.ofMillis(500));
        gateway = gateway(mockServer.baseUrl(), API_KEY, Duration.ofMillis(100), 30_000);

        gateway.start();

        assertThat(gateway.isRunning()).isTrue();
        assertThat(gateway.callTool(EXPOSED_TOOL, Map.of()).errorCode())
                .isEqualTo(McpErrorCode.MCP_TIMEOUT);
    }

    @Test
    void mapsConnectionFailureAndFailsOpen() {
        String unavailableUrl = mockServer.baseUrl();
        mockServer.close();
        mockServer = null;
        gateway = gateway(unavailableUrl, API_KEY, Duration.ofMillis(300), 30_000);

        gateway.start();

        assertThat(gateway.isRunning()).isTrue();
        assertThat(gateway.callTool(EXPOSED_TOOL, Map.of()).errorCode())
                .isEqualTo(McpErrorCode.MCP_UNAVAILABLE);
    }

    @Test
    void disabledMcpDoesNotCreateClient() {
        McpProperties properties = properties(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(1), 30_000);
        properties.setEnabled(false);
        AtomicInteger factoryCalls = new AtomicInteger();
        gateway = new SdkMcpToolGateway(properties, (serverId, server) -> {
            factoryCalls.incrementAndGet();
            throw new AssertionError("client must not be created");
        }, objectMapper);

        gateway.start();

        assertThat(factoryCalls).hasValue(0);
        assertThat(gateway.discoverTools()).isEmpty();
        assertThat(gateway.callTool(EXPOSED_TOOL, Map.of()).errorCode())
                .isEqualTo(McpErrorCode.MCP_DISABLED);
    }

    @Test
    void missingApiKeyDoesNotCreateClientOrBlockStartup() {
        McpProperties properties = properties(mockServer.baseUrl(), "", Duration.ofSeconds(1), 30_000);
        AtomicInteger factoryCalls = new AtomicInteger();
        gateway = new SdkMcpToolGateway(properties, (serverId, server) -> {
            factoryCalls.incrementAndGet();
            throw new AssertionError("client must not be created");
        }, objectMapper);

        gateway.start();

        assertThat(factoryCalls).hasValue(0);
        assertThat(gateway.isRunning()).isTrue();
        assertThat(gateway.callTool(EXPOSED_TOOL, Map.of()).errorCode())
                .isEqualTo(McpErrorCode.MCP_AUTH_FAILED);
    }

    @Test
    void retainsToolErrorContentAndRedactsCredentials() {
        gateway = gateway(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(2), 30_000);
        gateway.start();

        McpToolCallResult result = gateway.callTool(EXPOSED_TOOL, Map.of("mode", "tool-error"));

        assertThat(result.errorCode()).isEqualTo(McpErrorCode.MCP_TOOL_ERROR);
        assertThat(result.content()).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("text", "remote rejected Bearer *** and ***"));
        assertThat(result.errorMessage()).doesNotContain(API_KEY).contains("***");
        assertThat(result.structuredContent().toString()).doesNotContain(API_KEY);
        assertThat(result.metadata().toString()).doesNotContain(API_KEY);
    }

    @Test
    void rejectsOversizedResult() {
        gateway = gateway(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(2), 64);
        gateway.start();

        McpToolCallResult result = gateway.callTool(EXPOSED_TOOL, Map.of("mode", "large"));

        assertThat(result.errorCode()).isEqualTo(McpErrorCode.MCP_RESULT_TOO_LARGE);
        assertThat(result.content()).isEmpty();
        assertThat(result.structuredContent()).isEmpty();
    }

    @Test
    void mapsJsonRpcInvalidArguments() {
        gateway = gateway(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(2), 30_000);
        gateway.start();

        McpToolCallResult result = gateway.callTool(EXPOSED_TOOL, Map.of("mode", "invalid"));

        assertThat(result.errorCode()).isEqualTo(McpErrorCode.MCP_INVALID_ARGUMENTS);
        assertThat(result.errorMessage()).doesNotContain(API_KEY);
    }

    @Test
    void mapsAllowlistedButUndiscoveredToolAsNotFound() {
        McpProperties properties = properties(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(2), 30_000);
        properties.getServers().get("web-search").setAllowedTools(List.of("missing-tool"));
        gateway = new SdkMcpToolGateway(properties, new SdkMcpSyncClientFactory(), objectMapper);
        gateway.start();

        McpToolCallResult result = gateway.callTool("mcp.web-search.missing-tool", Map.of());

        assertThat(result.errorCode()).isEqualTo(McpErrorCode.MCP_TOOL_NOT_FOUND);
        assertThat(mockServer.callCount()).isZero();
    }

    @Test
    void mapsRepeatedPaginationCursorAsProtocolErrorAndFailsOpen() {
        mockServer.setRepeatCursor(true);
        gateway = gateway(mockServer.baseUrl(), API_KEY, Duration.ofSeconds(2), 30_000);

        gateway.start();

        assertThat(gateway.isRunning()).isTrue();
        assertThat(gateway.discoverTools()).isEmpty();
        assertThat(gateway.callTool(EXPOSED_TOOL, Map.of()).errorCode())
                .isEqualTo(McpErrorCode.MCP_PROTOCOL_ERROR);
    }

    private SdkMcpToolGateway gateway(String baseUrl, String apiKey, Duration requestTimeout, int maxResultChars) {
        return new SdkMcpToolGateway(
                properties(baseUrl, apiKey, requestTimeout, maxResultChars),
                new SdkMcpSyncClientFactory(),
                objectMapper
        );
    }

    private McpProperties properties(
            String baseUrl,
            String apiKey,
            Duration requestTimeout,
            int maxResultChars
    ) {
        McpProperties.Server server = new McpProperties.Server();
        server.setBaseUrl(baseUrl);
        server.setEndpoint("/mcp/");
        server.setApiKey(apiKey);
        server.setNamespace("mcp.web-search");
        server.setConnectTimeout(Duration.ofMillis(300));
        server.setRequestTimeout(requestTimeout);
        server.setMaxResultChars(maxResultChars);
        server.setAllowedTools(List.of("tavily-search"));

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setFailFast(false);
        properties.setServers(Map.of("web-search", server));
        return properties;
    }

    private void awaitDelete() {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!mockServer.methods().contains("DELETE") && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class MockMcpServer implements AutoCloseable {

        private final ObjectMapper objectMapper;
        private final HttpServer server;
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        private final List<String> listCursors = new CopyOnWriteArrayList<>();
        private final AtomicInteger callCount = new AtomicInteger();

        private volatile boolean unauthorized;
        private volatile boolean repeatCursor;
        private volatile boolean sessionEnabled;
        private volatile Duration responseDelay = Duration.ZERO;
        private volatile JsonNode lastCallArguments;

        private MockMcpServer(ObjectMapper objectMapper) throws IOException {
            this.objectMapper = objectMapper;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/mcp/", this::handle);
        }

        private void start() {
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void setUnauthorized(boolean unauthorized) {
            this.unauthorized = unauthorized;
        }

        private void setResponseDelay(Duration responseDelay) {
            this.responseDelay = responseDelay;
        }

        private void setRepeatCursor(boolean repeatCursor) {
            this.repeatCursor = repeatCursor;
        }

        private void setSessionEnabled(boolean sessionEnabled) {
            this.sessionEnabled = sessionEnabled;
        }

        private List<String> methods() {
            return List.copyOf(methods);
        }

        private List<String> authorizationHeaders() {
            return List.copyOf(authorizationHeaders);
        }

        private List<String> listCursors() {
            return List.copyOf(listCursors);
        }

        private int callCount() {
            return callCount.get();
        }

        private JsonNode lastCallArguments() {
            return lastCallArguments;
        }

        private void handle(HttpExchange exchange) throws IOException {
            methods.add(exchange.getRequestMethod());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizationHeaders.add(authorization == null ? "<missing>" : authorization);
            if (unauthorized) {
                send(exchange, 401, "unauthorized");
                return;
            }
            delay();
            if ("GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "");
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                send(exchange, 200, "");
                return;
            }

            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            if (!request.has("id")) {
                send(exchange, 202, "");
                return;
            }
            String method = request.path("method").asText();
            switch (method) {
                case "initialize" -> initialize(exchange, request);
                case "tools/list" -> listTools(exchange, request);
                case "tools/call" -> callTool(exchange, request);
                default -> sendError(exchange, request, -32601, "method not found");
            }
        }

        private void initialize(HttpExchange exchange, JsonNode request) throws IOException {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("protocolVersion", "2025-11-25");
            result.set("capabilities", objectMapper.createObjectNode().set("tools",
                    objectMapper.createObjectNode().put("listChanged", false)));
            result.set("serverInfo", objectMapper.createObjectNode()
                    .put("name", "mock-mcp")
                    .put("version", "1.0.0"));
            sendResult(exchange, request, result);
        }

        private void listTools(HttpExchange exchange, JsonNode request) throws IOException {
            String cursor = request.path("params").path("cursor").asText("");
            listCursors.add(cursor);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode tools = result.putArray("tools");
            if (cursor.isEmpty()) {
                tools.add(tool("tavily-search", true));
                tools.add(tool("hidden-tool", false));
                result.put("nextCursor", "page-2");
            } else if (!"page-2".equals(cursor)) {
                sendError(exchange, request, -32602, "invalid cursor");
                return;
            } else if (repeatCursor) {
                result.put("nextCursor", "page-2");
            }
            sendResult(exchange, request, result);
        }

        private ObjectNode tool(String name, boolean readOnly) {
            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("name", name);
            tool.put("description", "Mock search tool");
            tool.set("inputSchema", objectMapper.createObjectNode()
                    .put("type", "object")
                    .set("properties", objectMapper.createObjectNode()
                            .set("query", objectMapper.createObjectNode().put("type", "string"))));
            tool.set("annotations", objectMapper.createObjectNode().put("readOnlyHint", readOnly));
            return tool;
        }

        private void callTool(HttpExchange exchange, JsonNode request) throws IOException {
            callCount.incrementAndGet();
            lastCallArguments = request.path("params").path("arguments").deepCopy();
            String mode = lastCallArguments.path("mode").asText();
            if ("invalid".equals(mode)) {
                sendError(exchange, request, -32602, "invalid arguments");
                return;
            }

            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode text = content.addObject().put("type", "text");
            if ("tool-error".equals(mode)) {
                text.put("text", "remote rejected Bearer " + API_KEY + " and " + API_KEY);
                result.put("isError", true);
                result.set("structuredContent", objectMapper.createObjectNode().put("secret", API_KEY));
                result.set("_meta", objectMapper.createObjectNode().put("token", API_KEY));
            } else {
                text.put("text", "large".equals(mode) ? "x".repeat(500) : "search result");
                result.put("isError", false);
                result.set("structuredContent", objectMapper.createObjectNode()
                        .put("query", lastCallArguments.path("query").asText(""))
                        .set("results", objectMapper.createArrayNode()));
                result.set("_meta", objectMapper.createObjectNode().put("requestId", "request-1"));
            }
            sendResult(exchange, request, result);
        }

        private void sendResult(HttpExchange exchange, JsonNode request, JsonNode result) throws IOException {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", result);
            sendJson(exchange, 200, response);
        }

        private void sendError(HttpExchange exchange, JsonNode request, int code, String message) throws IOException {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("error", objectMapper.createObjectNode().put("code", code).put("message", message));
            sendJson(exchange, 200, response);
        }

        private void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (sessionEnabled) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "mock-session");
            }
            send(exchange, status, objectMapper.writeValueAsString(body));
        }

        private void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (!body.isEmpty() && exchange.getResponseHeaders().getFirst("Content-Type") == null) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }

        private void delay() {
            if (responseDelay.isZero()) {
                return;
            }
            try {
                Thread.sleep(responseDelay.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
