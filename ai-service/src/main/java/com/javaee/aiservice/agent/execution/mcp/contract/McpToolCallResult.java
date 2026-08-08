package com.javaee.aiservice.agent.execution.mcp.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loss-minimizing MCP result returned by the transport layer before Agent normalization.
 */
public record McpToolCallResult(
        String serverId,
        String remoteToolName,
        String exposedToolName,
        boolean error,
        List<Map<String, Object>> content,
        Map<String, Object> structuredContent,
        Map<String, Object> metadata,
        McpErrorCode errorCode,
        String errorMessage,
        long durationMs
) {

    public McpToolCallResult {
        content = immutableListCopy(content);
        structuredContent = immutableMapCopy(structuredContent);
        metadata = immutableMapCopy(metadata);
        errorCode = errorCode == null ? (error ? McpErrorCode.MCP_TOOL_ERROR : McpErrorCode.NONE) : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        durationMs = Math.max(0, durationMs);
    }

    public static McpToolCallResult success(
            String serverId,
            String remoteToolName,
            String exposedToolName,
            List<Map<String, Object>> content,
            Map<String, Object> structuredContent,
            Map<String, Object> metadata,
            long durationMs
    ) {
        return new McpToolCallResult(serverId, remoteToolName, exposedToolName, false, content,
                structuredContent, metadata, McpErrorCode.NONE, "", durationMs);
    }

    public static McpToolCallResult failure(
            String serverId,
            String remoteToolName,
            String exposedToolName,
            McpErrorCode errorCode,
            String errorMessage,
            long durationMs
    ) {
        return new McpToolCallResult(serverId, remoteToolName, exposedToolName, true, List.of(),
                Map.of(), Map.of(), errorCode, errorMessage, durationMs);
    }

    private static List<Map<String, Object>> immutableListCopy(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            copy.add(immutableMapCopy(item));
        }
        return List.copyOf(copy);
    }

    private static Map<String, Object> immutableMapCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
