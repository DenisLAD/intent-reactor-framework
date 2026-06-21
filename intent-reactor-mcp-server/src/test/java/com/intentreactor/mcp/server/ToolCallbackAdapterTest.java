package com.intentreactor.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.mcp.server.adapter.ToolCallbackAdapter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallbackAdapterTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Tool simpleTool(String name, String desc, Map<String, Object> schema, ToolResult result) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return desc;
            }

            @Override
            public Map<String, Object> getParameterSchema() {
                return schema;
            }

            @Override
            public ToolResult execute(ToolInput input) {
                return result;
            }

            @Override
            public boolean isRisky() {
                return false;
            }
        };
    }

    @Test
    void getToolDefinition_name_mappedCorrectly() {
        Tool tool = simpleTool("my_tool", "Does things", Map.of("type", "object"), ToolResult.ok("ok"));
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, objectMapper);
        assertThat(adapter.getToolDefinition().name()).isEqualTo("my_tool");
    }

    @Test
    void getToolDefinition_description_mappedCorrectly() {
        Tool tool = simpleTool("t", "Does things", Map.of(), ToolResult.ok("ok"));
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, objectMapper);
        assertThat(adapter.getToolDefinition().description()).isEqualTo("Does things");
    }

    @Test
    void getToolDefinition_inputSchema_isValidJson() throws Exception {
        Tool tool = simpleTool("t", "d",
                Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))),
                ToolResult.ok("ok"));
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, objectMapper);
        String schema = adapter.getToolDefinition().inputSchema();
        assertThat(schema).contains("object");
        // Verify it parses as valid JSON
        objectMapper.readTree(schema);
    }

    @Test
    void call_successfulExecution_returnsSerializedData() {
        Tool tool = simpleTool("t", "d", Map.of(), ToolResult.ok(Map.of("answer", 42)));
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, objectMapper);
        String result = adapter.call("{\"input\":\"test\"}");
        assertThat(result).contains("42");
    }

    @Test
    void call_toolResultError_returnsErrorString() {
        Tool tool = simpleTool("t", "d", Map.of(), ToolResult.error("something went wrong"));
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, objectMapper);
        String result = adapter.call("{}");
        assertThat(result).startsWith("ERROR: something went wrong");
    }

    @Test
    void call_toolThrowsException_returnsErrorString() {
        Tool tool = new Tool() {
            @Override
            public String getName() {
                return "t";
            }

            @Override
            public String getDescription() {
                return "d";
            }

            @Override
            public Map<String, Object> getParameterSchema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(ToolInput input) {
                throw new RuntimeException("exploded");
            }

            @Override
            public boolean isRisky() {
                return false;
            }
        };
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, objectMapper);
        String result = adapter.call("{}");
        assertThat(result).contains("exploded");
    }

    @Test
    void call_nullArguments_doesNotThrow() {
        Tool tool = simpleTool("t", "d", Map.of(), ToolResult.ok("ok"));
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, objectMapper);
        String result = adapter.call(null);
        assertThat(result).isNotNull();
    }

    @Test
    void call_emptySchema_usesDefaultSchemaJson() {
        Tool tool = simpleTool("t", "d", Map.of(), ToolResult.ok("ok"));
        ToolCallbackAdapter adapter = new ToolCallbackAdapter(tool, objectMapper);
        assertThat(adapter.getToolDefinition().inputSchema()).contains("object");
    }
}
