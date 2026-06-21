package com.intentreactor.mcp.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.config.IntentReactorAutoConfiguration;
import com.intentreactor.mcp.server.adapter.IntentReactorToolCallbackProvider;
import com.intentreactor.mcp.server.planner.PlannerMcpCallbackProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that bridges IntentReactor to Spring AI's MCP server infrastructure.
 *
 * <p>Registers two kinds of {@code ToolCallbackProvider} beans that Spring AI's
 * {@code ToolCallbackConverterAutoConfiguration} picks up and converts to
 * {@code SyncToolSpecification} objects for the MCP server:
 * <ul>
 *   <li>{@code expose-tools=true} — all registered {@link com.intentreactor.api.Tool} beans</li>
 *   <li>{@code expose-planner=true} — three planning tools backed by {@link IntentReactorService}</li>
 * </ul>
 *
 * <p>Requires {@code spring-ai-starter-mcp-server-webmvc} (or equivalent) on the
 * classpath to actually start the MCP server and handle SSE connections.
 */
@AutoConfiguration
@AutoConfigureAfter(IntentReactorAutoConfiguration.class)
@ConditionalOnClass(McpSchema.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "intent-reactor.mcp.server",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(McpServerIntegrationProperties.class)
public class IntentReactorMcpServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IntentReactorToolCallbackProvider.class)
    @ConditionalOnBean(ToolProvider.class)
    @ConditionalOnProperty(
            prefix = "intent-reactor.mcp.server",
            name = "expose-tools",
            havingValue = "true",
            matchIfMissing = true
    )
    public IntentReactorToolCallbackProvider intentReactorToolCallbackProvider(
            ToolProvider toolProvider,
            ObjectMapper objectMapper) {
        return new IntentReactorToolCallbackProvider(toolProvider, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(PlannerMcpCallbackProvider.class)
    @ConditionalOnBean(IntentReactorService.class)
    @ConditionalOnProperty(
            prefix = "intent-reactor.mcp.server",
            name = "expose-planner",
            havingValue = "true"
    )
    public PlannerMcpCallbackProvider intentReactorPlannerCallbackProvider(
            IntentReactorService intentReactorService,
            ObjectMapper objectMapper) {
        return new PlannerMcpCallbackProvider(intentReactorService, objectMapper);
    }
}
