package com.intentreactor.mcp.client.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.mcp.client.config.McpClientProperties;
import org.springframework.ai.tool.ToolCallback;

import java.util.Collections;
import java.util.Map;

/**
 * Adapts a Spring AI {@link ToolCallback} (obtained from an MCP server) to the
 * IntentReactor {@link Tool} interface.
 */
public class McpToolAdapter implements Tool {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolCallback delegate;
    private final McpClientProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> parameterSchema;

    public McpToolAdapter(ToolCallback delegate,
                          McpClientProperties properties,
                          ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.parameterSchema = parseSchema(delegate.getToolDefinition().inputSchema());
    }

    @Override
    public String getName() {
        return delegate.getToolDefinition().name();
    }

    @Override
    public String getDescription() {
        return delegate.getToolDefinition().description();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return parameterSchema;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        try {
            String json = objectMapper.writeValueAsString(
                    input.getParameters() != null ? input.getParameters() : Collections.emptyMap());
            String result = delegate.call(json);
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.error("MCP tool execution failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isRisky() {
        return properties.isTreatMcpToolsAsRisky()
                || properties.getRiskyToolNames().contains(getName());
    }

    @Override
    public boolean isGenerator() {
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(inputSchema, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
