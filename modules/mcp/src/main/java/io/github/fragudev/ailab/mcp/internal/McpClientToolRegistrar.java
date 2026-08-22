package io.github.fragudev.ailab.mcp.internal;

import io.github.fragudev.ailab.tools.ToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Discovers and registers every configured external MCP server's tools into the shared
 * {@link ToolRegistry}, once, on {@link ApplicationReadyEvent} — mirroring Phase 6's
 * {@code workflow.internal.WorkflowResumer} precedent exactly: waiting for the embedded server
 * (including this application's own MCP endpoint, in the self-connect case, docs/adr/
 * 0011-mcp-tool-exposure-boundaries.md) to already be accepting connections, rather than blocking
 * application startup on an external dependency's availability. {@code spring.ai.mcp.client.initialized}
 * is set to {@code false} in {@code application.yml} specifically so the handshake below — not
 * Spring AI's own autoconfiguration — is what triggers it, at the right point in the startup
 * sequence. A connection or discovery failure is logged and skipped, never fatal — the same
 * graceful-degrade philosophy as {@code rag.internal.QueryNormalizer}.
 *
 * <p>Spring AI's autoconfiguration wires every configured connection into one {@code List<McpSyncClient>}
 * bean (confirmed against the real autoconfiguration, not assumed — a per-connection
 * {@code Map<String, McpSyncClient>} isn't offered), so the connection's name for tool-name
 * prefixing comes from the server's own advertised identity ({@link McpSyncClient#getServerInfo()},
 * only available once {@link McpSyncClient#initialize()} has completed) rather than a Spring config
 * key.
 */
@Component
class McpClientToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpClientToolRegistrar.class);

    private final List<McpSyncClient> clients;
    private final ToolRegistry toolRegistry;
    private final McpProperties properties;

    McpClientToolRegistrar(List<McpSyncClient> clients, ToolRegistry toolRegistry, McpProperties properties) {
        this.clients = clients;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        for (int i = 0; i < clients.size(); i++) {
            registerToolsFrom(clients.get(i), i);
        }
    }

    private void registerToolsFrom(McpSyncClient client, int index) {
        String connectionName = "conn" + index;
        try {
            McpSchema.InitializeResult initialized = client.initialize();
            String serverName = initialized.serverInfo() == null
                    ? null
                    : initialized.serverInfo().name();
            if (serverName != null && !serverName.isBlank()) {
                connectionName = serverName;
            }
            for (McpSchema.Tool tool : client.listTools().tools()) {
                String prefixedName = "mcp:%s:%s".formatted(connectionName, tool.name());
                toolRegistry.register(new McpClientTool(client, tool, prefixedName, properties.client()));
                log.info("Registered MCP-client tool '{}' from connection '{}'", prefixedName, connectionName);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to discover tools from MCP connection '{}' — skipping, not fatal", connectionName, e);
        }
    }
}
