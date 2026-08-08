package com.javaee.aiservice.agent.execution.mcp.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockMcpServerTest {

    private static final String FIXTURE_BEARER = "acceptance-fixture-token";
    private static final String ACCEPT = "application/json, text/event-stream";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private MockMcpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = MockMcpServer.start(FIXTURE_BEARER);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void enforcesInitializationNotificationAndSessionHeaders() throws Exception {
        HttpResponse<String> initialize = initialize();

        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(initialize.headers().firstValue("MCP-Session-Id"))
                .contains(MockMcpServer.SESSION_ID);
        JsonNode initializeBody = json(initialize);
        assertThat(initializeBody.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(initializeBody.path("id").asInt()).isEqualTo(1);
        assertThat(initializeBody.path("result").path("protocolVersion").asText())
                .isEqualTo(MockMcpServer.PROTOCOL_VERSION);
        assertThat(initializeBody.path("result").path("capabilities").path("tools").isObject()).isTrue();

        HttpResponse<String> beforeNotification = post(request(2, "tools/list"), true);
        assertThat(beforeNotification.statusCode()).isEqualTo(200);
        assertThat(json(beforeNotification).path("error").path("message").asText())
                .isEqualTo("Server not initialized");

        HttpResponse<String> initialized = post(initializedNotification(), true);
        assertThat(initialized.statusCode()).isEqualTo(202);
        assertThat(initialized.body()).isEmpty();

        HttpResponse<String> list = post(request(3, "tools/list"), true);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).path("result").path("tools").size()).isEqualTo(2);

        HttpResponse<String> missingSession = post(request(4, "tools/list"), false);
        assertThat(missingSession.statusCode()).isEqualTo(400);
        assertThat(json(missingSession).path("error").path("message").asText())
                .isEqualTo("Missing or invalid MCP session headers");
    }

    @Test
    void exposesPaginationCompleteSchemaAndHostileAnnotationsForClientFiltering() throws Exception {
        initializeSession();

        JsonNode firstPage = json(post(request(10, "tools/list"), true)).path("result");
        assertThat(firstPage.path("nextCursor").asText()).isEqualTo(MockMcpServer.PAGE_CURSOR);
        assertThat(firstPage.path("tools").findValuesAsText("name"))
                .containsExactly("tavily-search", "tavily-extract");

        JsonNode search = firstPage.path("tools").get(0);
        JsonNode schema = search.path("inputSchema");
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required").get(0).asText()).isEqualTo("query");
        assertThat(schema.path("properties").path("query").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("max_results").path("type").asText()).isEqualTo("integer");
        assertThat(schema.path("properties").path("score_threshold").path("type").asText()).isEqualTo("number");
        assertThat(schema.path("properties").path("include_raw_content").path("type").asText())
                .isEqualTo("boolean");
        assertThat(schema.path("properties").path("include_domains").path("type").asText())
                .isEqualTo("array");
        assertThat(schema.path("properties").path("search_depth").path("enum").toString())
                .isEqualTo("[\"basic\",\"advanced\"]");
        assertThat(search.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(search.path("annotations").path("destructiveHint").asBoolean()).isTrue();

        ObjectNode secondPageRequest = request(11, "tools/list");
        secondPageRequest.putObject("params").put("cursor", MockMcpServer.PAGE_CURSOR);
        JsonNode secondPage = json(post(secondPageRequest, true)).path("result");
        assertThat(secondPage.has("nextCursor")).isFalse();
        assertThat(secondPage.path("tools").findValuesAsText("name"))
                .containsExactly("tavily-crawl", "tavily-research");
    }

    @Test
    void returnsStructuredTextFallbackEmptyAndToolErrorScenarios() throws Exception {
        initializeSession();

        JsonNode success = call(20, "fixture:success");
        assertThat(success.path("result").path("isError").asBoolean()).isFalse();
        assertThat(success.path("result").path("structuredContent").path("results").size()).isEqualTo(3);
        assertThat(success.path("result").path("structuredContent").path("results").findValuesAsText("url"))
                .containsExactly(
                        "https://example.invalid/source-a",
                        "https://example.invalid/source-a",
                        "https://example.invalid/source-b");
        assertThat(success.path("result").path("content").get(0).path("text").asText())
                .contains("Fixture result A");

        JsonNode textOnly = call(21, "fixture:text-only");
        assertThat(textOnly.path("result").has("structuredContent")).isFalse();
        assertThat(textOnly.path("result").path("content").get(0).path("text").asText())
                .contains("fixture:text-only", "source-a");

        JsonNode empty = call(22, "fixture:empty");
        assertThat(empty.path("result").path("isError").asBoolean()).isFalse();
        assertThat(empty.path("result").path("structuredContent").path("results").size()).isZero();

        JsonNode toolError = call(23, "fixture:error");
        assertThat(toolError.path("result").path("isError").asBoolean()).isTrue();
        assertThat(toolError.path("result").path("content").get(0).path("text").asText())
                .contains("rejected");
    }

    @Test
    void simulatesTimeoutProtocolErrorAndOversizedResultWithoutWeakeningPayloads() throws Exception {
        initializeSession();

        HttpRequest slowRequest = postBuilder(callRequest(30, "fixture:slow"), true)
                .timeout(Duration.ofMillis(50))
                .build();
        assertThatThrownBy(() -> httpClient.send(slowRequest, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(HttpTimeoutException.class);

        HttpResponse<String> protocolError = post(callRequest(31, "fixture:protocol-error"), true);
        assertThat(protocolError.statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> objectMapper.readTree(protocolError.body()))
                .isInstanceOf(Exception.class);

        JsonNode oversized = call(32, "fixture:oversized");
        String text = oversized.path("result").path("content").get(0).path("text").asText();
        assertThat(text).hasSize(MockMcpServer.DEFAULT_OVERSIZED_RESULT_CHARS);
        assertThat(text.length()).isGreaterThan(30_000);
    }

    @Test
    void returnsProtocolErrorsForUnknownToolAndMissingRequiredQuery() throws Exception {
        initializeSession();

        ObjectNode unknown = request(40, "tools/call");
        unknown.putObject("params").put("name", "tavily-extract").putObject("arguments").put("query", "blocked");
        JsonNode unknownResponse = json(post(unknown, true));
        assertThat(unknownResponse.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(unknownResponse.path("error").path("message").asText()).contains("Unknown tool");

        ObjectNode missingQuery = request(41, "tools/call");
        missingQuery.putObject("params").put("name", MockMcpServer.SEARCH_TOOL).putObject("arguments");
        JsonNode missingQueryResponse = json(post(missingQuery, true));
        assertThat(missingQueryResponse.path("result").path("isError").asBoolean()).isTrue();
        assertThat(missingQueryResponse.path("result").path("content").get(0).path("text").asText())
                .isEqualTo("query is required");
    }

    @Test
    void rejectsBadAuthenticationOriginAndMediaNegotiationWithoutEchoingCredentials() throws Exception {
        String rejectedCredential = "credential-must-not-be-echoed";
        HttpResponse<String> unauthorized = httpClient.send(
                basePostBuilder(initializeRequest())
                        .header("Authorization", "Bearer " + rejectedCredential)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.body()).contains("Unauthorized").doesNotContain(rejectedCredential);

        HttpResponse<String> untrustedOrigin = httpClient.send(
                authorizedPostBuilder(initializeRequest())
                        .header("Origin", "https://attacker.invalid")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(untrustedOrigin.statusCode()).isEqualTo(403);
        assertThat(untrustedOrigin.body()).contains("Forbidden origin").doesNotContain(FIXTURE_BEARER);

        HttpResponse<String> invalidAccept = httpClient.send(
                HttpRequest.newBuilder(server.endpoint())
                        .header("Authorization", "Bearer " + FIXTURE_BEARER)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initializeRequest().toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(invalidAccept.statusCode()).isEqualTo(406);
        assertThat(invalidAccept.body()).doesNotContain(FIXTURE_BEARER);

        HttpResponse<String> get = httpClient.send(
                HttpRequest.newBuilder(server.endpoint()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(405);
    }

    @Test
    void recordsProtocolEvidenceButNeverRetainsBearerValue() throws Exception {
        initializeSession();
        call(50, "fixture:audit");

        List<MockMcpServer.RecordedRequest> requests = server.recordedRequests();
        assertThat(requests).hasSize(3);
        assertThat(requests).allMatch(MockMcpServer.RecordedRequest::authorizationPresent);
        assertThat(requests.get(0).body().path("method").asText()).isEqualTo("initialize");
        assertThat(requests.get(1).body().path("method").asText()).isEqualTo("notifications/initialized");
        assertThat(requests.get(2).body().path("params").path("arguments").path("query").asText())
                .isEqualTo("fixture:audit");
        assertThat(requests.toString()).doesNotContain(FIXTURE_BEARER, "Authorization", "Bearer ");
    }

    @Test
    void acceptsGracefulSessionDeletionAndRejectsFurtherUse() throws Exception {
        initializeSession();

        HttpRequest delete = HttpRequest.newBuilder(server.endpoint())
                .header("Authorization", "Bearer " + FIXTURE_BEARER)
                .header("MCP-Session-Id", MockMcpServer.SESSION_ID)
                .DELETE()
                .build();
        HttpResponse<String> deleted = httpClient.send(delete, HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(204);

        JsonNode afterDelete = json(post(request(60, "tools/list"), true));
        assertThat(afterDelete.path("error").path("message").asText()).isEqualTo("Server not initialized");
    }

    @Test
    void simulatesNetworkUnavailabilityAfterServerShutdown() {
        HttpRequest request = authorizedPostBuilder(initializeRequest())
                .timeout(Duration.ofMillis(500))
                .build();
        server.close();
        server = null;

        assertThatThrownBy(() -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(java.io.IOException.class);
    }

    private void initializeSession() throws Exception {
        assertThat(initialize().statusCode()).isEqualTo(200);
        assertThat(post(initializedNotification(), true).statusCode()).isEqualTo(202);
    }

    private HttpResponse<String> initialize() throws Exception {
        return post(initializeRequest(), false);
    }

    private ObjectNode initializeRequest() {
        ObjectNode request = request(1, "initialize");
        ObjectNode params = request.putObject("params");
        params.put("protocolVersion", MockMcpServer.PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "omnidoc-t04-tests").put("version", "1.0.0");
        return request;
    }

    private ObjectNode initializedNotification() {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        return notification;
    }

    private JsonNode call(int id, String query) throws Exception {
        return json(post(callRequest(id, query), true));
    }

    private ObjectNode callRequest(int id, String query) {
        ObjectNode request = request(id, "tools/call");
        request.putObject("params")
                .put("name", MockMcpServer.SEARCH_TOOL)
                .putObject("arguments")
                .put("query", query);
        return request;
    }

    private ObjectNode request(int id, String method) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        return request;
    }

    private HttpResponse<String> post(JsonNode request, boolean withSession) throws Exception {
        return httpClient.send(postBuilder(request, withSession).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder postBuilder(JsonNode request, boolean withSession) {
        HttpRequest.Builder builder = authorizedPostBuilder(request);
        if (withSession) {
            builder.header("MCP-Session-Id", MockMcpServer.SESSION_ID)
                    .header("MCP-Protocol-Version", MockMcpServer.PROTOCOL_VERSION);
        }
        return builder;
    }

    private HttpRequest.Builder authorizedPostBuilder(JsonNode request) {
        return basePostBuilder(request).header("Authorization", "Bearer " + FIXTURE_BEARER);
    }

    private HttpRequest.Builder basePostBuilder(JsonNode request) {
        return HttpRequest.newBuilder(server.endpoint())
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()));
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }
}
