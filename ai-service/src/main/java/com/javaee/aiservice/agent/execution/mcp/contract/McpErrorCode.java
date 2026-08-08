package com.javaee.aiservice.agent.execution.mcp.contract;

/**
 * Stable error categories shared by the MCP transport and Agent integration layers.
 */
public enum McpErrorCode {
    NONE,
    MCP_DISABLED,
    MCP_UNAVAILABLE,
    MCP_AUTH_FAILED,
    MCP_TIMEOUT,
    MCP_TOOL_NOT_ALLOWED,
    MCP_TOOL_NOT_FOUND,
    MCP_INVALID_ARGUMENTS,
    MCP_TOOL_ERROR,
    MCP_PROTOCOL_ERROR,
    MCP_RESULT_TOO_LARGE
}
