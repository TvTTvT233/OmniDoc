package com.javaee.aiservice.agent.execution.mcp.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-neutral tool metadata discovered from an MCP server.
 */
public record McpToolDescriptor(
        String serverId,
        String remoteName,
        String exposedName,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> annotations
) {

    public McpToolDescriptor {
        serverId = requireText(serverId, "serverId");
        remoteName = requireText(remoteName, "remoteName");
        exposedName = requireText(exposedName, "exposedName");
        description = description == null ? "" : description;
        inputSchema = immutableCopy(inputSchema);
        annotations = immutableCopy(annotations);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
