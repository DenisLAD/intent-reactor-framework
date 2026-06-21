package com.intentreactor.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.mcp.client.adapter.McpToolAdapter;
import com.intentreactor.mcp.client.config.McpClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolAdapterTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private McpClientProperties properties;
    private ToolCallback mockCallback;

    @BeforeEach
    void setUp() {
        properties = new McpClientProperties();
        mockCallback = mock(ToolCallback.class);
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("test_tool")
                .description("A test tool")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                .build();
        when(mockCallback.getToolDefinition()).thenReturn(definition);
    }

    @Test
    void getName_returnsDelegateToolName() {
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        assertThat(adapter.getName()).isEqualTo("test_tool");
    }

    @Test
    void getDescription_returnsDelegateDescription() {
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        assertThat(adapter.getDescription()).isEqualTo("A test tool");
    }

    @Test
    void getParameterSchema_parsesJsonSchemaToMap() {
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        Map<String, Object> schema = adapter.getParameterSchema();
        assertThat(schema).containsKey("type");
        assertThat(schema.get("type")).isEqualTo("object");
    }

    @Test
    void getParameterSchema_returnsEmptyMapForInvalidSchema() {
        ToolDefinition badDef = DefaultToolDefinition.builder()
                .name("bad_tool")
                .description("Bad schema")
                .inputSchema("{not valid json}")
                .build();
        ToolCallback badCallback = mock(ToolCallback.class);
        when(badCallback.getToolDefinition()).thenReturn(badDef);
        McpToolAdapter adapter = new McpToolAdapter(badCallback, properties, objectMapper);
        assertThat(adapter.getParameterSchema()).isEmpty();
    }

    @Test
    void execute_successfulCall_returnsToolResultOk() {
        when(mockCallback.call(anyString())).thenReturn("result value");
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        ToolResult result = adapter.execute(new ToolInput(Map.of("query", "test"), null));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("result value");
    }

    @Test
    void execute_callbackThrowsException_returnsToolResultError() {
        when(mockCallback.call(anyString())).thenThrow(new RuntimeException("MCP server down"));
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        ToolResult result = adapter.execute(new ToolInput(Map.of(), null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("MCP server down");
    }

    @Test
    void execute_nullParameters_doesNotThrow() {
        when(mockCallback.call(anyString())).thenReturn("ok");
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        ToolResult result = adapter.execute(new ToolInput(null, null));
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void isRisky_defaultProperties_returnsFalse() {
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        assertThat(adapter.isRisky()).isFalse();
    }

    @Test
    void isRisky_treatAllAsRisky_returnsTrue() {
        properties.setTreatMcpToolsAsRisky(true);
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        assertThat(adapter.isRisky()).isTrue();
    }

    @Test
    void isRisky_toolInRiskyList_returnsTrue() {
        properties.setRiskyToolNames(Set.of("test_tool"));
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        assertThat(adapter.isRisky()).isTrue();
    }

    @Test
    void isGenerator_alwaysFalse() {
        McpToolAdapter adapter = new McpToolAdapter(mockCallback, properties, objectMapper);
        assertThat(adapter.isGenerator()).isFalse();
    }
}
