package com.intentreactor.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intentreactor.api.Action;
import com.intentreactor.api.ConfirmationManager;
import com.intentreactor.api.IntentPreprocessor;
import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Planner;
import com.intentreactor.api.PromptContextProvider;
import com.intentreactor.api.SessionStore;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.event.IntentReactorEventLogger;
import com.intentreactor.core.planner.DefaultReACTPlanner;
import com.intentreactor.core.planner.LATSPlanner;
import com.intentreactor.core.planner.MessageCompressor;
import com.intentreactor.core.planner.ReflexionPlanner;
import com.intentreactor.core.util.PromptLoader;
import com.intentreactor.core.planner.search.DefaultSearchTree;
import com.intentreactor.core.planner.search.SearchTree;
import com.intentreactor.core.preprocessor.DefaultIntentPreprocessor;
import com.intentreactor.core.service.DefaultConfirmationManager;
import com.intentreactor.core.service.IntentReactorServiceImpl;
import com.intentreactor.core.session.FileSystemSessionStore;
import com.intentreactor.core.session.InMemorySessionStore;
import com.intentreactor.core.tool.DefaultToolProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(IntentReactorProperties.class)
public class IntentReactorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper intentReactorObjectMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        // Register SearchTree polymorphic type info for session serialization
        mapper.registerModule(new SimpleModule()
                .addAbstractTypeMapping(SearchTree.class, DefaultSearchTree.class)
                .addAbstractTypeMapping(PlanStep.class, SimplePlanStep.class)
                .addAbstractTypeMapping(Action.class, SimpleAction.class));
        return mapper;
    }

    @Bean
    @ConditionalOnClass(name = "org.apache.hc.client5.http.impl.classic.CloseableHttpClient")
    @ConditionalOnMissingBean(RestClientCustomizer.class)
    public RestClientCustomizer apacheHttp5ClientCustomizer() {
        return builder -> builder.requestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient intentReactorChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    @Bean
    @ConditionalOnMissingBean(ConfirmationManager.class)
    public ConfirmationManager confirmationManager(IntentReactorProperties properties) {
        return new DefaultConfirmationManager(properties);
    }

    // ---- Context compression (opt-in) ----

    @Bean
    @ConditionalOnMissingBean(MessageCompressor.class)
    @ConditionalOnProperty(
            prefix = "intent-reactor.planning.context-window.compression",
            name = "enabled",
            havingValue = "true")
    public MessageCompressor messageCompressor(ChatClient chatClient,
                                               IntentReactorProperties properties,
                                               ApplicationEventPublisher eventPublisher) {
        return new MessageCompressor(chatClient, properties, new PromptLoader(), eventPublisher);
    }

    // ---- ReACT planner (default) ----

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy",
            havingValue = "react", matchIfMissing = true)
    public Planner reactPlanner(ChatClient chatClient,
                                ToolProvider toolProvider,
                                IntentReactorProperties properties,
                                ObjectMapper objectMapper,
                                @Autowired(required = false) List<PromptContextProvider> promptContextProviders,
                                @Autowired(required = false) MessageCompressor messageCompressor,
                                @Autowired(required = false) io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner p = new DefaultReACTPlanner(chatClient, toolProvider, properties, objectMapper, providers, messageCompressor);
        return meterRegistry != null ? wrapWithMetrics(p, "react", meterRegistry) : p;
    }

    // ---- Reflexion planner ----

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "reflexion")
    public Planner reflexionPlanner(ChatClient chatClient,
                                    ToolProvider toolProvider,
                                    IntentReactorProperties properties,
                                    ObjectMapper objectMapper,
                                    @Autowired(required = false) List<PromptContextProvider> promptContextProviders,
                                    @Autowired(required = false) MessageCompressor messageCompressor,
                                    @Autowired(required = false) io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner delegate = new DefaultReACTPlanner(chatClient, toolProvider, properties, objectMapper, providers, messageCompressor);
        Planner p = new ReflexionPlanner(delegate, chatClient, properties);
        return meterRegistry != null ? wrapWithMetrics(p, "reflexion", meterRegistry) : p;
    }

    // ---- LATS planner ----

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "lats")
    public Planner latsPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper,
                               @Autowired(required = false) List<PromptContextProvider> promptContextProviders,
                               @Autowired(required = false) MessageCompressor messageCompressor,
                               @Autowired(required = false) io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner p = new LATSPlanner(chatClient, toolProvider, properties, objectMapper, providers, messageCompressor);
        return meterRegistry != null ? wrapWithMetrics(p, "lats", meterRegistry) : p;
    }

    // ---- Main service ----

    @Bean
    @ConditionalOnMissingBean(IntentReactorService.class)
    public IntentReactorService intentReactorService(IntentPreprocessor preprocessor,
                                                     Planner planner,
                                                     SessionStore sessionStore,
                                                     ToolProvider toolProvider,
                                                     ApplicationEventPublisher eventPublisher,
                                                     IntentReactorProperties properties,
                                                     ConfirmationManager confirmationManager,
                                                     ChatClient chatClient,
                                                     ObjectMapper objectMapper) {
        return new IntentReactorServiceImpl(preprocessor, planner, sessionStore,
                toolProvider, eventPublisher, properties, confirmationManager,
                chatClient, objectMapper);
    }

    // ---- Core infrastructure beans (declared explicitly — @ComponentScan is not allowed on @AutoConfiguration) ----

    @Bean
    @ConditionalOnMissingBean(IntentPreprocessor.class)
    public IntentPreprocessor defaultIntentPreprocessor(ChatClient chatClient,
                                                        ObjectMapper objectMapper,
                                                        IntentReactorProperties properties) {
        return new DefaultIntentPreprocessor(chatClient, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ToolProvider.class)
    public ToolProvider defaultToolProvider(List<Tool> tools) {
        return new DefaultToolProvider(tools);
    }

    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    @ConditionalOnProperty(prefix = "intent-reactor.session", name = "store",
            havingValue = "in-memory", matchIfMissing = true)
    public SessionStore inMemorySessionStore() {
        return new InMemorySessionStore();
    }

    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    @ConditionalOnProperty(prefix = "intent-reactor.session", name = "store", havingValue = "filesystem")
    public SessionStore fileSystemSessionStore(IntentReactorProperties properties, ObjectMapper objectMapper) {
        return new FileSystemSessionStore(properties, objectMapper);
    }

    // ---- Default event logger ----

    @Bean
    @ConditionalOnMissingBean(IntentReactorEventLogger.class)
    @ConditionalOnProperty(prefix = "intent-reactor.logging", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public IntentReactorEventLogger intentReactorEventLogger() {
        return new IntentReactorEventLogger();
    }

    // ---- Micrometer helper ----

    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    private Planner wrapWithMetrics(Planner planner, String strategy,
                                    io.micrometer.core.instrument.MeterRegistry registry) {
        return new com.intentreactor.core.planner.MicrometerPlannerDecorator(planner, registry, strategy);
    }
}
