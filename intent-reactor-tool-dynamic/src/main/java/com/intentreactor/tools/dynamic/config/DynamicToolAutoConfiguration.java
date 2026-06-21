package com.intentreactor.tools.dynamic.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.config.IntentReactorAutoConfiguration;
import com.intentreactor.tools.dynamic.api.ScriptRepository;
import com.intentreactor.tools.dynamic.repository.InMemoryScriptRepository;
import com.intentreactor.tools.dynamic.repository.InvalidationAwareScriptRepository;
import com.intentreactor.tools.dynamic.repository.JdbcScriptRepository;
import com.intentreactor.tools.dynamic.sandbox.RhinoSandbox;
import com.intentreactor.tools.dynamic.sandbox.SandboxClassShutter;
import com.intentreactor.tools.dynamic.sandbox.TimeLimitedContextFactory;
import com.intentreactor.tools.dynamic.tool.DynamicScriptTool;
import com.intentreactor.tools.dynamic.tool.DynamicToolProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@AutoConfigureBefore(IntentReactorAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "intent-reactor.tools.dynamic-scripting",
        name = "enabled",
        havingValue = "true"
)
@ConditionalOnClass(org.mozilla.javascript.Context.class)
@EnableConfigurationProperties(DynamicScriptingProperties.class)
public class DynamicToolAutoConfiguration {

    @Bean
    public TimeLimitedContextFactory timeLimitedContextFactory(DynamicScriptingProperties properties) {
        return new TimeLimitedContextFactory(properties.getMaxExecutionTime());
    }

    @Bean
    public SandboxClassShutter sandboxClassShutter(DynamicScriptingProperties properties) {
        return new SandboxClassShutter(properties.getAllowedClasses());
    }

    @Bean
    public RhinoSandbox rhinoSandbox(TimeLimitedContextFactory contextFactory,
                                     SandboxClassShutter classShutter) {
        return new RhinoSandbox(contextFactory, classShutter);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "intent-reactor.tools.dynamic-scripting",
            name = "script-repository",
            havingValue = "in-memory",
            matchIfMissing = true
    )
    public ScriptRepository inMemoryScriptRepository() {
        return new InMemoryScriptRepository();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "intent-reactor.tools.dynamic-scripting",
            name = "script-repository",
            havingValue = "jdbc"
    )
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnBean(type = "org.springframework.jdbc.core.JdbcTemplate")
    public ScriptRepository jdbcScriptRepository() {
        return new JdbcScriptRepository();
    }

    @Bean
    public InvalidationAwareScriptRepository invalidationAwareScriptRepository(ScriptRepository delegate) {
        return new InvalidationAwareScriptRepository(delegate);
    }

    @Bean
    @ConditionalOnMissingBean(ToolProvider.class)
    public DynamicToolProvider dynamicToolProvider(List<Tool> staticTools,
                                                   InvalidationAwareScriptRepository scriptRepository,
                                                   RhinoSandbox rhinoSandbox) {
        DynamicToolProvider provider = new DynamicToolProvider(staticTools, scriptRepository, rhinoSandbox);
        scriptRepository.setProvider(provider);
        return provider;
    }

    @Bean
    public DynamicScriptTool dynamicScriptTool(ChatClient chatClient,
                                               InvalidationAwareScriptRepository scriptRepository,
                                               RhinoSandbox rhinoSandbox,
                                               DynamicScriptingProperties properties,
                                               @Autowired(required = false) ObjectMapper objectMapper) {
        ObjectMapper om = objectMapper != null ? objectMapper : new ObjectMapper();
        return new DynamicScriptTool(chatClient, scriptRepository, rhinoSandbox, om, properties);
    }
}
