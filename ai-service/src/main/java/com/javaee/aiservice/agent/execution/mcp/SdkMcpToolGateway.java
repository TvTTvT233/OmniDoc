package com.javaee.aiservice.agent.execution.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.mcp.contract.McpErrorCode;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolCallResult;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolDescriptor;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolGateway;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle-managed MCP SDK gateway. Startup discovery is fail-open unless explicitly configured otherwise.
 */
public final class SdkMcpToolGateway implements McpToolGateway, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SdkMcpToolGateway.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final McpProperties properties;
    private final McpSyncClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final McpSecretSanitizer sanitizer;

    private volatile Map<String, ClientSession> sessions = Map.of();
    private volatile Map<String, ToolRoute> routes = Map.of();
    private volatile Map<String, McpErrorCode> startupErrors = Map.of();
    private volatile boolean running;

    public SdkMcpToolGateway(
            McpProperties properties,
            McpSyncClientFactory clientFactory,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.objectMapper = objectMapper;
        this.sanitizer = new McpSecretSanitizer(properties.getServers().values().stream()
                .map(McpProperties.Server::getApiKey)
                .toList());
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.isEnabled()) {
            running = true;
            log.info("MCP client is disabled; no remote clients were created");
            return;
        }

        Map<String, ClientSession> newSessions = new LinkedHashMap<>();
        Map<String, ToolRoute> newRoutes = new LinkedHashMap<>();
        Map<String, McpErrorCode> newErrors = new LinkedHashMap<>();

        for (Map.Entry<String, McpProperties.Server> entry : properties.getServers().entrySet()) {
            String serverId = entry.getKey();
            McpProperties.Server server = entry.getValue();
            if (!server.isEnabled()) {
                continue;
            }
            if (server.getApiKey() == null || server.getApiKey().isBlank()) {
                newErrors.put(serverId, McpErrorCode.MCP_AUTH_FAILED);
                log.warn("MCP server '{}' is unavailable because its API key is missing", serverId);
                continue;
            }

            McpSyncClient client = null;
            try {
                client = clientFactory.create(serverId, server);
                client.initialize();
                List<McpToolDescriptor> descriptors = discoverAllowedTools(serverId, server, client);
                Map<String, ToolRoute> serverRoutes = new LinkedHashMap<>();
                for (McpToolDescriptor descriptor : descriptors) {
                    ToolRoute route = new ToolRoute(descriptor, serverId, server, client);
                    if (newRoutes.containsKey(descriptor.exposedName())
                            || serverRoutes.putIfAbsent(descriptor.exposedName(), route) != null) {
                        throw new IllegalStateException("Duplicate MCP exposed tool name");
                    }
                }
                newRoutes.putAll(serverRoutes);
                newSessions.put(serverId, new ClientSession(client));
                log.info("MCP server '{}' initialized with {} allowed tool(s)", serverId, descriptors.size());
            } catch (RuntimeException failure) {
                closeQuietly(serverId, client);
                McpErrorCode code = McpExceptionMapper.map(failure);
                newErrors.put(serverId, code);
                log.warn("MCP server '{}' failed during startup: {}", serverId,
                        McpExceptionMapper.publicMessage(code));
                if (properties.isFailFast()) {
                    closeSessions(newSessions);
                    throw new IllegalStateException(McpExceptionMapper.publicMessage(code));
                }
            }
        }

        sessions = Collections.unmodifiableMap(new LinkedHashMap<>(newSessions));
        routes = Collections.unmodifiableMap(new LinkedHashMap<>(newRoutes));
        startupErrors = Collections.unmodifiableMap(new LinkedHashMap<>(newErrors));
        running = true;
    }

    @Override
    public synchronized void stop() {
        Map<String, ClientSession> currentSessions = sessions;
        sessions = Map.of();
        routes = Map.of();
        startupErrors = Map.of();
        running = false;
        closeSessions(currentSessions);
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public List<McpToolDescriptor> discoverTools() {
        return routes.values().stream().map(ToolRoute::descriptor).toList();
    }

    @Override
    public boolean supports(String exposedToolName) {
        return exposedToolName != null && routes.containsKey(exposedToolName);
    }

    @Override
    public McpToolCallResult callTool(String exposedToolName, Map<String, Object> arguments) {
        long startedAt = System.nanoTime();
        ConfiguredTarget configured = findConfiguredTarget(exposedToolName);
        if (!properties.isEnabled()) {
            return failure(configured, exposedToolName, McpErrorCode.MCP_DISABLED, startedAt);
        }
        if (configured == null) {
            return McpToolCallResult.failure("", "", safeName(exposedToolName),
                    McpErrorCode.MCP_TOOL_NOT_ALLOWED,
                    McpExceptionMapper.publicMessage(McpErrorCode.MCP_TOOL_NOT_ALLOWED), elapsedMillis(startedAt));
        }
        if (!configured.server().isEnabled()) {
            return failure(configured, exposedToolName, McpErrorCode.MCP_DISABLED, startedAt);
        }

        ToolRoute route = routes.get(exposedToolName);
        if (route == null) {
            McpErrorCode startupError = startupErrors.get(configured.serverId());
            return failure(configured, exposedToolName,
                    startupError == null ? McpErrorCode.MCP_TOOL_NOT_FOUND : startupError, startedAt);
        }

        try {
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(route.descriptor().remoteName())
                    .arguments(arguments == null ? Map.of() : new LinkedHashMap<>(arguments))
                    .build();
            McpSchema.CallToolResult sdkResult = route.client().callTool(request);
            if (sdkResult == null) {
                throw new IllegalStateException("MCP tools/call returned no result");
            }

            List<Map<String, Object>> content = convertContent(sdkResult.content());
            Map<String, Object> structuredContent = convertStructuredContent(sdkResult.structuredContent());
            Map<String, Object> metadata = sanitizer.sanitizeMap(sdkResult.meta());
            long durationMs = elapsedMillis(startedAt);

            if (resultSize(content, structuredContent, metadata) > route.server().getMaxResultChars()) {
                return McpToolCallResult.failure(route.serverId(), route.descriptor().remoteName(), exposedToolName,
                        McpErrorCode.MCP_RESULT_TOO_LARGE,
                        McpExceptionMapper.publicMessage(McpErrorCode.MCP_RESULT_TOO_LARGE), durationMs);
            }
            if (Boolean.TRUE.equals(sdkResult.isError())) {
                return new McpToolCallResult(route.serverId(), route.descriptor().remoteName(), exposedToolName,
                        true, content, structuredContent, metadata, McpErrorCode.MCP_TOOL_ERROR,
                        toolErrorMessage(content), durationMs);
            }
            return McpToolCallResult.success(route.serverId(), route.descriptor().remoteName(), exposedToolName,
                    content, structuredContent, metadata, durationMs);
        } catch (RuntimeException failure) {
            McpErrorCode code = McpExceptionMapper.map(failure);
            log.warn("MCP tool call failed for server '{}' and tool '{}': {}", route.serverId(),
                    route.descriptor().remoteName(), McpExceptionMapper.publicMessage(code));
            return McpToolCallResult.failure(route.serverId(), route.descriptor().remoteName(), exposedToolName,
                    code, McpExceptionMapper.publicMessage(code), elapsedMillis(startedAt));
        }
    }

    private List<McpToolDescriptor> discoverAllowedTools(
            String serverId,
            McpProperties.Server server,
            McpSyncClient client
    ) {
        Set<String> allowlist = new LinkedHashSet<>(server.getAllowedTools());
        Set<String> cursors = new LinkedHashSet<>();
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        Set<String> remoteNames = new LinkedHashSet<>();
        String cursor = null;

        do {
            // The SDK's no-arg listTools() auto-expands all pages. Use the cursor overload for every page
            // so this layer can detect a broken server that repeats a cursor instead of looping forever.
            McpSchema.ListToolsResult page = client.listTools(cursor);
            if (page == null) {
                throw new IllegalStateException("MCP tools/list returned no result");
            }
            for (McpSchema.Tool tool : page.tools()) {
                if (!allowlist.contains(tool.name())) {
                    continue;
                }
                if (!remoteNames.add(tool.name())) {
                    throw new IllegalStateException("MCP tools/list returned a duplicate tool");
                }
                String exposedName = server.getNamespace().trim() + "." + tool.name();
                descriptors.add(new McpToolDescriptor(
                        serverId,
                        tool.name(),
                        exposedName,
                        sanitizer.sanitize(tool.description()),
                        sanitizer.sanitizeMap(tool.inputSchema()),
                        convertAnnotations(tool.annotations())
                ));
            }
            cursor = page.nextCursor();
            if (cursor != null && cursor.isBlank()) {
                cursor = null;
            }
            if (cursor != null && !cursors.add(cursor)) {
                throw new IllegalStateException("MCP tools/list returned a repeated nextCursor");
            }
        } while (cursor != null);

        return descriptors;
    }

    private Map<String, Object> convertAnnotations(McpSchema.ToolAnnotations annotations) {
        if (annotations == null) {
            return Map.of();
        }
        return sanitizer.sanitizeMap(objectMapper.convertValue(annotations, MAP_TYPE));
    }

    private List<Map<String, Object>> convertContent(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> converted = new ArrayList<>(content.size());
        for (McpSchema.Content item : content) {
            converted.add(sanitizer.sanitizeMap(objectMapper.convertValue(item, MAP_TYPE)));
        }
        return converted;
    }

    private Map<String, Object> convertStructuredContent(Object structuredContent) {
        if (structuredContent == null) {
            return Map.of();
        }
        return sanitizer.sanitizeMap(objectMapper.convertValue(structuredContent, MAP_TYPE));
    }

    private int resultSize(
            List<Map<String, Object>> content,
            Map<String, Object> structuredContent,
            Map<String, Object> metadata
    ) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "content", content,
                    "structuredContent", structuredContent,
                    "metadata", metadata
            )).length();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize MCP result", exception);
        }
    }

    private String toolErrorMessage(List<Map<String, Object>> content) {
        for (Map<String, Object> item : content) {
            Object text = item.get("text");
            if (text instanceof String message && !message.isBlank()) {
                String sanitized = sanitizer.sanitize(message);
                return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
            }
        }
        return McpExceptionMapper.publicMessage(McpErrorCode.MCP_TOOL_ERROR);
    }

    private ConfiguredTarget findConfiguredTarget(String exposedToolName) {
        if (exposedToolName == null || exposedToolName.isBlank()) {
            return null;
        }
        for (Map.Entry<String, McpProperties.Server> entry : properties.getServers().entrySet()) {
            McpProperties.Server server = entry.getValue();
            if (server.getNamespace() == null) {
                continue;
            }
            for (String remoteName : server.getAllowedTools()) {
                if (remoteName != null
                        && exposedToolName.equals(server.getNamespace().trim() + "." + remoteName)) {
                    return new ConfiguredTarget(entry.getKey(), remoteName, server);
                }
            }
        }
        return null;
    }

    private McpToolCallResult failure(
            ConfiguredTarget target,
            String exposedToolName,
            McpErrorCode code,
            long startedAt
    ) {
        return McpToolCallResult.failure(target == null ? "" : target.serverId(),
                target == null ? "" : target.remoteName(), safeName(exposedToolName), code,
                McpExceptionMapper.publicMessage(code), elapsedMillis(startedAt));
    }

    private void closeSessions(Map<String, ClientSession> sessionsToClose) {
        sessionsToClose.forEach((serverId, session) -> closeQuietly(serverId, session.client()));
    }

    private void closeQuietly(String serverId, McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            if (!client.closeGracefully()) {
                log.warn("MCP server '{}' did not close within the SDK grace period", serverId);
            }
        } catch (RuntimeException failure) {
            log.warn("MCP server '{}' failed while closing", serverId);
        }
    }

    private static String safeName(String value) {
        return value == null ? "" : value;
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private record ClientSession(McpSyncClient client) {
    }

    private record ToolRoute(
            McpToolDescriptor descriptor,
            String serverId,
            McpProperties.Server server,
            McpSyncClient client
    ) {
    }

    private record ConfiguredTarget(String serverId, String remoteName, McpProperties.Server server) {
    }
}
