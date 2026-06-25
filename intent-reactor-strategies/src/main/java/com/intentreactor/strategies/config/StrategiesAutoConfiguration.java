package com.intentreactor.strategies.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Planner;
import com.intentreactor.api.PromptContextProvider;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.config.IntentReactorAutoConfiguration;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.planner.DefaultReACTPlanner;
import com.intentreactor.strategies.cot.ChainOfThoughtPlanner;
import com.intentreactor.strategies.cot.ZeroShotCoTPlanner;
import com.intentreactor.strategies.decomposition.LeastToMostPlanner;
import com.intentreactor.strategies.decomposition.PlanAndSolvePlanner;
import com.intentreactor.strategies.decomposition.SelfAskPlanner;
import com.intentreactor.strategies.hierarchical.HTPPlanner;
import com.intentreactor.strategies.knowledge.KnowAgentPlanner;
import com.intentreactor.strategies.meta.SelfDiscoverPlanner;
import com.intentreactor.strategies.meta.StormPlanner;
import com.intentreactor.strategies.modular.MAPPlanner;
import com.intentreactor.strategies.refinement.ReflectionPlanner;
import com.intentreactor.strategies.refinement.StepBackPlanner;
import com.intentreactor.strategies.search.GraphOfThoughtsPlanner;
import com.intentreactor.strategies.search.ReTreValPlanner;
import com.intentreactor.strategies.search.TreeOfThoughtsPlanner;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@AutoConfigureBefore(IntentReactorAutoConfiguration.class)
@ConditionalOnClass(ChatClient.class)
@EnableConfigurationProperties(StrategiesProperties.class)
public class StrategiesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "cot")
    public Planner cotPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                              IntentReactorProperties props, ObjectMapper objectMapper,
                              StrategiesProperties strategiesProperties,
                              @Autowired(required = false) List<PromptContextProvider> promptContextProviders) {
        ChatClient chatClient = chatClientBuilder.build();
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner delegate = new DefaultReACTPlanner(chatClient, toolProvider, props, objectMapper, providers);
        return new ChainOfThoughtPlanner(delegate, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "zero-shot-cot")
    public Planner zeroShotCoTPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                      IntentReactorProperties props, ObjectMapper objectMapper,
                                      @Autowired(required = false) List<PromptContextProvider> promptContextProviders) {
        ChatClient chatClient = chatClientBuilder.build();
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner delegate = new DefaultReACTPlanner(chatClient, toolProvider, props, objectMapper, providers);
        return new ZeroShotCoTPlanner(delegate);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "step-back")
    public Planner stepBackPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                   IntentReactorProperties props, ObjectMapper objectMapper,
                                   @Autowired(required = false) List<PromptContextProvider> promptContextProviders) {
        ChatClient chatClient = chatClientBuilder.build();
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner delegate = new DefaultReACTPlanner(chatClient, toolProvider, props, objectMapper, providers);
        return new StepBackPlanner(delegate, chatClient, props, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "reflection")
    public Planner reflectionPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                     IntentReactorProperties props, ObjectMapper objectMapper,
                                     StrategiesProperties strategiesProperties,
                                     @Autowired(required = false) List<PromptContextProvider> promptContextProviders) {
        ChatClient chatClient = chatClientBuilder.build();
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner delegate = new DefaultReACTPlanner(chatClient, toolProvider, props, objectMapper, providers);
        return new ReflectionPlanner(delegate, chatClient, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "self-ask")
    public Planner selfAskPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                  IntentReactorProperties props, ObjectMapper objectMapper,
                                  StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new SelfAskPlanner(chatClient, toolProvider, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "least-to-most")
    public Planner leastToMostPlanner(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper,
                                      StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new LeastToMostPlanner(chatClient, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "plan-and-solve")
    public Planner planAndSolvePlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                       IntentReactorProperties props, ObjectMapper objectMapper,
                                       StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new PlanAndSolvePlanner(chatClient, toolProvider, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "tot")
    public Planner treeOfThoughtsPlanner(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper,
                                         StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new TreeOfThoughtsPlanner(chatClient, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "got")
    public Planner graphOfThoughtsPlanner(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper,
                                          StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new GraphOfThoughtsPlanner(chatClient, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "self-discover")
    public Planner selfDiscoverPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                       IntentReactorProperties props, ObjectMapper objectMapper,
                                       StrategiesProperties strategiesProperties,
                                       @Autowired(required = false) List<PromptContextProvider> promptContextProviders) {
        ChatClient chatClient = chatClientBuilder.build();
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner delegate = new DefaultReACTPlanner(chatClient, toolProvider, props, objectMapper, providers);
        return new SelfDiscoverPlanner(delegate, chatClient, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "storm")
    public Planner stormPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                ObjectMapper objectMapper, StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new StormPlanner(chatClient, toolProvider, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "retreval")
    public Planner retrevalPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                   ObjectMapper objectMapper, StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new ReTreValPlanner(chatClient, toolProvider, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "map")
    public Planner mapPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                              ObjectMapper objectMapper, StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new MAPPlanner(chatClient, toolProvider, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "htp")
    public Planner htpPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                              ObjectMapper objectMapper, StrategiesProperties strategiesProperties) {
        ChatClient chatClient = chatClientBuilder.build();
        return new HTPPlanner(chatClient, toolProvider, objectMapper, strategiesProperties);
    }

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "knowagent")
    public Planner knowAgentPlanner(ChatClient.Builder chatClientBuilder, ToolProvider toolProvider,
                                    IntentReactorProperties props, ObjectMapper objectMapper,
                                    StrategiesProperties strategiesProperties,
                                    @Autowired(required = false) List<PromptContextProvider> promptContextProviders) {
        ChatClient chatClient = chatClientBuilder.build();
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner delegate = new DefaultReACTPlanner(chatClient, toolProvider, props, objectMapper, providers);
        return new KnowAgentPlanner(delegate, chatClient, toolProvider, objectMapper, strategiesProperties);
    }
}
