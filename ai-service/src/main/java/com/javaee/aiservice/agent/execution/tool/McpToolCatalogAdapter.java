package com.javaee.aiservice.agent.execution.tool;

import com.javaee.aiservice.agent.execution.mcp.contract.McpToolDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies the local allowlist and publishes complete MCP discovery snapshots to the central tool registry.
 */
@Component
public class McpToolCatalogAdapter {

    private final AgentToolRegistry toolRegistry;
    private final McpToolDefinitionMapper definitionMapper;

    @Autowired
    public McpToolCatalogAdapter(AgentToolRegistry toolRegistry) {
        this(toolRegistry, new McpToolDefinitionMapper());
    }

    McpToolCatalogAdapter(AgentToolRegistry toolRegistry, McpToolDefinitionMapper definitionMapper) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.definitionMapper = Objects.requireNonNull(definitionMapper, "definitionMapper must not be null");
    }

    public List<AgentToolDefinition> refreshTools(String serverId, Collection<String> allowedRemoteNames,
                                                  Collection<McpToolDescriptor> discoveredTools) {
        return refreshTools(serverId, definitionMapper.namespaceFor(serverId), allowedRemoteNames, discoveredTools);
    }

    public List<AgentToolDefinition> refreshTools(String serverId, String namespace,
                                                  Collection<String> allowedRemoteNames,
                                                  Collection<McpToolDescriptor> discoveredTools) {
        requireText(serverId, "serverId");
        Objects.requireNonNull(allowedRemoteNames, "allowedRemoteNames must not be null");
        Objects.requireNonNull(discoveredTools, "discoveredTools must not be null");

        Set<String> allowlist = new LinkedHashSet<>();
        for (String allowedRemoteName : allowedRemoteNames) {
            allowlist.add(requireText(allowedRemoteName, "allowed remote tool name"));
        }

        List<AgentToolDefinition> definitions = new ArrayList<>();
        for (McpToolDescriptor descriptor : discoveredTools) {
            Objects.requireNonNull(descriptor, "discovered MCP tool must not be null");
            if (!serverId.equals(descriptor.serverId())) {
                throw new IllegalArgumentException("Discovered MCP tool belongs to a different server: "
                        + descriptor.serverId());
            }
            if (!allowlist.contains(descriptor.remoteName())) {
                continue;
            }
            definitions.add(definitionMapper.map(descriptor, namespace, allowlist));
        }

        toolRegistry.replaceMcpTools(serverId, definitions);
        return List.copyOf(definitions);
    }

    public int removeServerTools(String serverId) {
        return toolRegistry.removeMcpTools(serverId);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
