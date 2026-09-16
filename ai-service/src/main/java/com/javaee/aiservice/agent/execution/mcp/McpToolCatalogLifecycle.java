package com.javaee.aiservice.agent.execution.mcp;

import com.javaee.aiservice.agent.execution.mcp.contract.McpToolDescriptor;
import com.javaee.aiservice.agent.execution.mcp.contract.McpToolGateway;
import com.javaee.aiservice.agent.execution.tool.McpToolCatalogAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Publishes the gateway's startup discovery snapshot to the Agent tool registry.
 *
 * <p>The gateway starts at phase 0. This bridge starts later so only initialized, allowlisted
 * descriptors reach the planner. Shutdown runs in reverse order and removes dynamic tools before
 * the remote clients close.</p>
 */
public final class McpToolCatalogLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalogLifecycle.class);
    private static final int CATALOG_PHASE = 100;

    private final McpProperties properties;
    private final McpToolGateway gateway;
    private final Supplier<McpToolCatalogAdapter> catalogAdapterSupplier;
    private volatile McpToolCatalogAdapter activeCatalogAdapter;
    private volatile boolean running;

    McpToolCatalogLifecycle(
            McpProperties properties,
            McpToolGateway gateway,
            McpToolCatalogAdapter catalogAdapter
    ) {
        this(properties, gateway, () -> catalogAdapter);
    }

    McpToolCatalogLifecycle(
            McpProperties properties,
            McpToolGateway gateway,
            Supplier<McpToolCatalogAdapter> catalogAdapterSupplier
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.catalogAdapterSupplier = Objects.requireNonNull(
                catalogAdapterSupplier, "catalogAdapterSupplier must not be null");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        McpToolCatalogAdapter catalogAdapter = catalogAdapterSupplier.get();
        if (catalogAdapter == null) {
            log.info("MCP tool catalog adapter is unavailable; transport remains isolated from the Agent catalog");
            running = true;
            return;
        }
        activeCatalogAdapter = catalogAdapter;

        Map<String, List<McpToolDescriptor>> descriptorsByServer;
        try {
            descriptorsByServer = gateway.discoverTools().stream()
                    .collect(Collectors.groupingBy(McpToolDescriptor::serverId));
        } catch (RuntimeException discoveryFailure) {
            if (properties.isFailFast()) {
                throw discoveryFailure;
            }
            log.warn("MCP tool catalog discovery snapshot is unavailable; native tools remain active");
            running = true;
            return;
        }

        properties.getServers().forEach((serverId, server) -> publishServerSnapshot(
                catalogAdapter, serverId, server, descriptorsByServer.getOrDefault(serverId, List.of())));
        running = true;
    }

    private void publishServerSnapshot(
            McpToolCatalogAdapter catalogAdapter,
            String serverId,
            McpProperties.Server server,
            List<McpToolDescriptor> descriptors
    ) {
        try {
            if (!properties.isEnabled() || !server.isEnabled()) {
                catalogAdapter.removeServerTools(serverId);
                return;
            }
            catalogAdapter.refreshTools(
                    serverId,
                    server.getNamespace(),
                    server.getAllowedTools(),
                    descriptors
            );
            log.info("Published {} MCP tool(s) from server '{}' to the Agent catalog",
                    descriptors.size(), serverId);
        } catch (RuntimeException publishFailure) {
            if (properties.isFailFast()) {
                throw publishFailure;
            }
            log.warn("MCP tools from server '{}' were not published; its previous catalog snapshot was retained",
                    serverId);
        }
    }

    @Override
    public synchronized void stop() {
        McpToolCatalogAdapter catalogAdapter = activeCatalogAdapter;
        activeCatalogAdapter = null;
        if (catalogAdapter != null) {
            properties.getServers().keySet().forEach(serverId -> {
                try {
                    catalogAdapter.removeServerTools(serverId);
                } catch (RuntimeException removalFailure) {
                    log.warn("MCP tools from server '{}' could not be removed during shutdown", serverId);
                }
            });
        }
        running = false;
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
        return CATALOG_PHASE;
    }
}
