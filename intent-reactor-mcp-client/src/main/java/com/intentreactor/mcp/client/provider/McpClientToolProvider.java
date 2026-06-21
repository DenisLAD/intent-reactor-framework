package com.intentreactor.mcp.client.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.mcp.client.adapter.McpToolAdapter;
import com.intentreactor.mcp.client.config.McpClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolsChangedEvent;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.context.ApplicationListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ToolProvider} that merges statically registered {@link Tool} beans with tools
 * discovered from external MCP servers.
 *
 * <p>Follows the same double-checked-lock caching pattern as {@code DynamicToolProvider}.
 * Cache is invalidated when {@link McpToolsChangedEvent} is published (MCP server
 * reports a tool list change).
 */
public class McpClientToolProvider implements ToolProvider, ApplicationListener<McpToolsChangedEvent> {

    private static final Logger log = LoggerFactory.getLogger(McpClientToolProvider.class);

    private final List<Tool> staticTools;
    private final SyncMcpToolCallbackProvider mcpProvider;
    private final McpClientProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean cacheValid = new AtomicBoolean(false);
    private volatile List<Tool> mcpToolsCache = List.of();

    public McpClientToolProvider(List<Tool> staticTools,
                                 SyncMcpToolCallbackProvider mcpProvider,
                                 McpClientProperties properties,
                                 ObjectMapper objectMapper) {
        this.staticTools = List.copyOf(staticTools);
        this.mcpProvider = mcpProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        log.info("McpClientToolProvider initialized with {} static tools and {} MCP server connections",
                staticTools.size(), properties.getServers().size());
    }

    @Override
    public List<Tool> getAvailableTools(SessionState sessionState) {
        List<Tool> result = new ArrayList<>(staticTools);
        result.addAll(loadMcpTools());
        return List.copyOf(result);
    }

    @Override
    public void onApplicationEvent(McpToolsChangedEvent event) {
        cacheValid.set(false);
        log.debug("MCP tools cache invalidated due to McpToolsChangedEvent");
    }

    private List<Tool> loadMcpTools() {
        if (!cacheValid.get()) {
            synchronized (this) {
                if (!cacheValid.get()) {
                    try {
                        List<Tool> loaded = Arrays.stream(mcpProvider.getToolCallbacks())
                                .map(cb -> (Tool) new McpToolAdapter(cb, properties, objectMapper))
                                .toList();
                        mcpToolsCache = List.copyOf(loaded);
                        cacheValid.set(true);
                        log.debug("Loaded {} tools from MCP servers", loaded.size());
                    } catch (Exception e) {
                        log.warn("Failed to load tools from MCP servers: {}", e.getMessage());
                    }
                }
            }
        }
        return mcpToolsCache;
    }
}
