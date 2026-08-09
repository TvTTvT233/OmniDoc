package com.javaee.aiservice.agent.execution.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * MCP Java SDK 2.0.0 client factory using the JDK HttpClient Streamable HTTP transport.
 */
final class SdkMcpSyncClientFactory implements McpSyncClientFactory {

    private static final String STREAMABLE_HTTP = "streamable-http";

    @Override
    public McpSyncClient create(String serverId, McpProperties.Server properties) {
        validate(serverId, properties);

        String authorization = "Bearer " + properties.getApiKey().trim();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(properties.getBaseUrl().trim())
                .endpoint(properties.getEndpoint().trim())
                .connectTimeout(properties.getConnectTimeout())
                .requestBuilder(HttpRequest.newBuilder())
                .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                        builder.header("Authorization", authorization))
                .build();

        return McpClient.sync(transport)
                .requestTimeout(properties.getRequestTimeout())
                .initializationTimeout(properties.getRequestTimeout())
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .clientInfo(McpSchema.Implementation.builder("OmniDoc MCP Client", "1.0.0").build())
                .build();
    }

    private static void validate(String serverId, McpProperties.Server properties) {
        requireText(serverId, "serverId");
        if (!STREAMABLE_HTTP.equals(properties.getTransport())) {
            throw new IllegalArgumentException("Only streamable-http MCP transport is supported");
        }
        URI baseUri = parseUri(properties.getBaseUrl(), "base-url");
        if (!baseUri.isAbsolute() || baseUri.getHost() == null) {
            throw new IllegalArgumentException("MCP base-url must be an absolute HTTP(S) URL");
        }
        if (!("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))) {
            throw new IllegalArgumentException("MCP base-url must use HTTP or HTTPS");
        }
        if (baseUri.getRawQuery() != null || baseUri.getRawUserInfo() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("MCP base-url must not contain user info, query parameters or fragments");
        }
        String endpoint = requireText(properties.getEndpoint(), "endpoint");
        URI endpointUri = parseUri(endpoint, "endpoint");
        if (endpointUri.isAbsolute() || endpointUri.getRawQuery() != null || endpointUri.getRawFragment() != null) {
            throw new IllegalArgumentException("MCP endpoint must be a path without query parameters or fragments");
        }
        requirePositive(properties.getConnectTimeout(), "connect-timeout");
        requirePositive(properties.getRequestTimeout(), "request-timeout");
        if (properties.getMaxResultChars() <= 0) {
            throw new IllegalArgumentException("MCP max-result-chars must be positive");
        }
        requireText(properties.getApiKey(), "api-key");
        requireText(properties.getNamespace(), "namespace");
    }

    private static URI parseUri(String value, String field) {
        try {
            return URI.create(requireText(value, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid MCP " + field, exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MCP " + field + " must not be blank");
        }
        return value.trim();
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("MCP " + field + " must be positive");
        }
    }
}
