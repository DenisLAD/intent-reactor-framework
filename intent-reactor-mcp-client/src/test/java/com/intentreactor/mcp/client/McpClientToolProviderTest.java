package com.intentreactor.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.mcp.client.config.McpClientProperties;
import com.intentreactor.mcp.client.provider.McpClientToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.mcp.McpToolsChangedEvent;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
class McpClientToolProviderTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private SyncMcpToolCallbackProvider mockMcpProvider;
    private McpClientProperties properties;
    private Tool staticTool;

    @BeforeEach
    void setUp() {
        mockMcpProvider = mock(SyncMcpToolCallbackProvider.class);
        properties = new McpClientProperties();
        staticTool = new Tool() {
            @Override
            public String getName() {
                return "static_tool";
            }

            @Override
            public String getDescription() {
                return "Static";
            }

            @Override
            public Map<String, Object> getParameterSchema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(ToolInput input) {
                return ToolResult.ok("ok");
            }

            @Override
            public boolean isRisky() {
                return false;
            }
        };
    }

    private ToolCallback makeCallback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name).description("desc").inputSchema("{\"type\":\"object\"}").build());
        when(cb.call(anyString())).thenReturn("result");
        return cb;
    }

    @Test
    void getAvailableTools_mergesStaticAndMcpTools() {
        ToolCallback mcpCb = makeCallback("mcp_weather");
        when(mockMcpProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{mcpCb});

        McpClientToolProvider provider = new McpClientToolProvider(
                List.of(staticTool), mockMcpProvider, properties, objectMapper);

        List<Tool> tools = provider.getAvailableTools(null);
        assertThat(tools).hasSize(2);
        assertThat(tools).extracting(Tool::getName).containsExactlyInAnyOrder("static_tool", "mcp_weather");
    }

    @Test
    void getAvailableTools_cachesToolsOnSecondCall() {
        when(mockMcpProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        McpClientToolProvider provider = new McpClientToolProvider(
                List.of(), mockMcpProvider, properties, objectMapper);

        provider.getAvailableTools(null);
        provider.getAvailableTools(null);

        // getToolCallbacks() called exactly once (cached)
        verify(mockMcpProvider, times(1)).getToolCallbacks();
    }

    @Test
    void onMcpToolsChangedEvent_invalidatesCache() {
        when(mockMcpProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        McpClientToolProvider provider = new McpClientToolProvider(
                List.of(), mockMcpProvider, properties, objectMapper);

        provider.getAvailableTools(null);
        provider.onApplicationEvent(mock(McpToolsChangedEvent.class));
        provider.getAvailableTools(null);

        // After invalidation, getToolCallbacks() is called again
        verify(mockMcpProvider, times(2)).getToolCallbacks();
    }

    @Test
    void getAvailableTools_returnsImmutableList() {
        when(mockMcpProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        McpClientToolProvider provider = new McpClientToolProvider(
                List.of(staticTool), mockMcpProvider, properties, objectMapper);

        List<Tool> tools = provider.getAvailableTools(new SessionState("test"));
        assertThat(tools).isUnmodifiable();
    }

    @Test
    void getAvailableTools_mcpProviderThrows_returnsOnlyStaticTools() {
        when(mockMcpProvider.getToolCallbacks()).thenThrow(new RuntimeException("MCP down"));
        McpClientToolProvider provider = new McpClientToolProvider(
                List.of(staticTool), mockMcpProvider, properties, objectMapper);

        List<Tool> tools = provider.getAvailableTools(null);
        // Should still return static tools even if MCP fails
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("static_tool");
    }
}
