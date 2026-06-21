package com.intentreactor.mcp.server.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Collections;
import java.util.Map;

/**
 * Adapts an IntentReactor {@link Tool} to Spring AI's {@link ToolCallback} interface so
 * that Spring AI's MCP server auto-configuration can pick it up automatically via
 * {@code ToolCallbackConverterAutoConfiguration}.
 */
public class ToolCallbackAdapter implements ToolCallback {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Tool tool;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public ToolCallbackAdapter(Tool tool, ObjectMapper objectMapper) {
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.definition = DefaultToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .inputSchema(serializeSchema(tool))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> params = toolInput == null || toolInput.isBlank()
                    ? Collections.emptyMap()
                    : objectMapper.readValue(toolInput, MAP_TYPE);
            ToolResult result = tool.execute(new ToolInput(params, null));
            if (result.isSuccess()) {
                return result.getData() != null
                        ? objectMapper.writeValueAsString(result.getData())
                        : "";
            }
            return "ERROR: " + result.getErrorMessage();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String serializeSchema(Tool t) {
        try {
            Map<String, Object> schema = t.getParameterSchema();
            if (schema == null || schema.isEmpty()) {
                return "{\"type\":\"object\",\"properties\":{}}";
            }
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }
}
