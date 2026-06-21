package com.intentreactor.mcp.server.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * Bridges IntentReactor's {@link ToolProvider} to Spring AI's {@link ToolCallbackProvider}.
 *
 * <p>Spring AI's {@code ToolCallbackConverterAutoConfiguration} picks up all
 * {@code ToolCallbackProvider} beans and converts them to {@code SyncToolSpecification}
 * instances registered with the MCP server automatically.
 */
public class IntentReactorToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(IntentReactorToolCallbackProvider.class);

    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;

    public IntentReactorToolCallbackProvider(ToolProvider toolProvider, ObjectMapper objectMapper) {
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        try {
            return toolProvider.getAvailableTools(new SessionState("mcp-server"))
                    .stream()
                    .filter(t -> !t.isGenerator())
                    .map(t -> (ToolCallback) new ToolCallbackAdapter(t, objectMapper))
                    .toArray(ToolCallback[]::new);
        } catch (Exception e) {
            log.warn("Failed to retrieve tools for MCP server registration: {}", e.getMessage());
            return new ToolCallback[0];
        }
    }
}
