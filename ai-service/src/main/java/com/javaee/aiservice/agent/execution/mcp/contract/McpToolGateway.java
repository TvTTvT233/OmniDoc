package com.javaee.aiservice.agent.execution.mcp.contract;

import java.util.List;
import java.util.Map;

/**
 * Boundary between MCP transport concerns and the existing Agent tool system.
 */
public interface McpToolGateway {

    /**
     * Discovers the currently allowed tools. Implementations must not expose tools outside the configured allowlist.
     */
    List<McpToolDescriptor> discoverTools();

    /**
     * Returns whether an exposed, namespaced tool can currently be dispatched by this gateway.
     */
    boolean supports(String exposedToolName);

    /**
     * Calls an exposed tool using validated Agent arguments.
     */
    McpToolCallResult callTool(String exposedToolName, Map<String, Object> arguments);
}
