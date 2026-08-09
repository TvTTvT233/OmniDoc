package com.javaee.aiservice.agent.execution.mcp;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * Creates an MCP SDK client without connecting it. Kept as a seam for lifecycle tests.
 */
@FunctionalInterface
public interface McpSyncClientFactory {

    McpSyncClient create(String serverId, McpProperties.Server properties);
}
