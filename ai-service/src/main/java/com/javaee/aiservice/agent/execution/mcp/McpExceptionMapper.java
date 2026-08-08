package com.javaee.aiservice.agent.execution.mcp;

import com.javaee.aiservice.agent.execution.mcp.contract.McpErrorCode;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

final class McpExceptionMapper {

    private McpExceptionMapper() {
    }

    static McpErrorCode map(Throwable failure) {
        for (Throwable current = failure; current != null; current = nextCause(current)) {
            if (current instanceof McpHttpClientTransportAuthorizationException) {
                return McpErrorCode.MCP_AUTH_FAILED;
            }
            if (current instanceof TimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return McpErrorCode.MCP_TIMEOUT;
            }
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException) {
                return McpErrorCode.MCP_UNAVAILABLE;
            }
            if (current instanceof McpError mcpError) {
                int code = mcpError.getJsonRpcError().code();
                if (code == McpSchema.ErrorCodes.METHOD_NOT_FOUND) {
                    return McpErrorCode.MCP_TOOL_NOT_FOUND;
                }
                if (code == McpSchema.ErrorCodes.INVALID_PARAMS) {
                    return McpErrorCode.MCP_INVALID_ARGUMENTS;
                }
                return McpErrorCode.MCP_PROTOCOL_ERROR;
            }
            if (current instanceof McpTransportException transportException) {
                String message = String.valueOf(transportException.getMessage()).toLowerCase(Locale.ROOT);
                if (message.contains("status code 5") || message.contains("server error")) {
                    return McpErrorCode.MCP_UNAVAILABLE;
                }
            }
            if (current instanceof IOException) {
                return McpErrorCode.MCP_UNAVAILABLE;
            }
        }
        return McpErrorCode.MCP_PROTOCOL_ERROR;
    }

    static String publicMessage(McpErrorCode code) {
        return switch (code) {
            case MCP_DISABLED -> "MCP or the configured server is disabled";
            case MCP_UNAVAILABLE -> "The MCP server is unavailable";
            case MCP_AUTH_FAILED -> "MCP authentication failed";
            case MCP_TIMEOUT -> "The MCP request timed out";
            case MCP_TOOL_NOT_ALLOWED -> "The MCP tool is not allowed";
            case MCP_TOOL_NOT_FOUND -> "The MCP tool was not found on the remote server";
            case MCP_INVALID_ARGUMENTS -> "The MCP tool arguments are invalid";
            case MCP_TOOL_ERROR -> "The MCP tool returned an error";
            case MCP_RESULT_TOO_LARGE -> "The MCP result exceeded the configured size limit";
            case MCP_PROTOCOL_ERROR -> "The MCP server returned an invalid protocol response";
            case NONE -> "";
        };
    }

    private static Throwable nextCause(Throwable failure) {
        return failure.getCause() == failure ? null : failure.getCause();
    }
}
