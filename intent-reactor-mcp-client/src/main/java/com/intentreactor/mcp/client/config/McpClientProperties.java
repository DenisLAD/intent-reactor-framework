package com.intentreactor.mcp.client.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "intent-reactor.mcp.client")
public class McpClientProperties {

    private boolean enabled = false;

    private List<McpServerConfig> servers = new ArrayList<>();

    /**
     * Apply this prefix to all tool names from MCP servers when prefixToolNames=true
     */
    private boolean treatMcpToolsAsRisky = false;

    /**
     * Tool names that are always considered risky regardless of server
     */
    private Set<String> riskyToolNames = new HashSet<>();

    public enum TransportType {
        SSE, STDIO
    }

    @Getter
    @Setter
    public static class McpServerConfig {

        /**
         * Logical name used as default tool name prefix
         */
        private String name;

        private TransportType transport = TransportType.SSE;

        // --- SSE transport ---
        private String url;
        private String ssePath = "/sse";
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);

        // --- STDIO transport ---
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();

        /**
         * Whether to prefix tool names with the server name
         */
        private boolean prefixToolNames = true;

        /**
         * Explicit prefix override — null means use server name
         */
        private String toolNamePrefix;
    }
}
