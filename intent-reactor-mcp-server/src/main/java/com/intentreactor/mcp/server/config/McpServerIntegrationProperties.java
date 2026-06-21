package com.intentreactor.mcp.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "intent-reactor.mcp.server")
public class McpServerIntegrationProperties {

    /**
     * Master switch for this auto-configuration
     */
    private boolean enabled = true;

    /**
     * Expose all registered Tool beans as MCP tools
     */
    private boolean exposeTools = true;

    /**
     * Expose IntentReactorService as intent_reactor_process / proceed / session tools
     */
    private boolean exposePlanner = false;

    private String serverName = "intent-reactor";

    private String serverVersion = "1.0.0";
}
