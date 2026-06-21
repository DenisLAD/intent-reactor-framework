package com.intentreactor.mcp.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.config.IntentReactorAutoConfiguration;
import com.intentreactor.mcp.client.provider.McpClientToolProvider;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration
@AutoConfigureBefore(IntentReactorAutoConfiguration.class)
@ConditionalOnProperty(prefix = "intent-reactor.mcp.client", name = "enabled", havingValue = "true")
@ConditionalOnClass(McpSyncClient.class)
@EnableConfigurationProperties(McpClientProperties.class)
public class McpClientAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpClientAutoConfiguration.class);

    @Bean
    public List<McpSyncClient> intentReactorMcpSyncClients(
            McpClientProperties properties, ObjectMapper objectMapper) {
        List<McpSyncClient> clients = new ArrayList<>();
        for (McpClientProperties.McpServerConfig serverConfig : properties.getServers()) {
            try {
                McpClientTransport transport = buildTransport(serverConfig, objectMapper);
                McpSyncClient client = io.modelcontextprotocol.client.McpClient
                        .sync(transport)
                        .clientInfo(new McpSchema.Implementation("intent-reactor", "1.0.0"))
                        .build();
                clients.add(client);
                log.info("Created MCP client for server '{}' (transport={})",
                        serverConfig.getName(), serverConfig.getTransport());
            } catch (Exception e) {
                log.warn("Failed to create MCP client for server '{}': {}",
                        serverConfig.getName(), e.getMessage());
            }
        }
        return clients;
    }

    /**
     * Initialize MCP connections after context is ready to avoid blocking startup
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeMcpClients(ApplicationReadyEvent event) {
        try {
            List<McpSyncClient> clients = event.getApplicationContext()
                    .getBean("intentReactorMcpSyncClients", List.class);
            for (McpSyncClient client : clients) {
                try {
                    client.initialize();
                    log.info("MCP client initialized: {}", client.getClientInfo().name());
                } catch (Exception e) {
                    log.warn("MCP client initialization failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Could not initialize MCP clients: {}", e.getMessage());
        }
    }

    @Bean
    public SyncMcpToolCallbackProvider intentReactorMcpToolCallbackProvider(
            List<McpSyncClient> intentReactorMcpSyncClients) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(intentReactorMcpSyncClients)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(ToolProvider.class)
    public McpClientToolProvider mcpClientToolProvider(
            List<Tool> staticTools,
            SyncMcpToolCallbackProvider intentReactorMcpToolCallbackProvider,
            McpClientProperties properties,
            ObjectMapper objectMapper) {
        return new McpClientToolProvider(staticTools, intentReactorMcpToolCallbackProvider,
                properties, objectMapper);
    }

    @Bean
    public DisposableBean mcpClientShutdownHook(List<McpSyncClient> intentReactorMcpSyncClients) {
        return () -> intentReactorMcpSyncClients.forEach(client -> {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        });
    }

    private McpClientTransport buildTransport(McpClientProperties.McpServerConfig config,
                                              ObjectMapper objectMapper) {
        return switch (config.getTransport()) {
            case STDIO -> {
                ServerParameters params = ServerParameters.builder(config.getCommand())
                        .args(config.getArgs())
                        .env(config.getEnv())
                        .build();
                yield new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper));
            }
            case SSE -> {
                String baseUrl = config.getUrl();
                yield HttpClientSseClientTransport.builder(baseUrl)
                        .sseEndpoint(config.getSsePath())
                        .build();
            }
        };
    }
}
