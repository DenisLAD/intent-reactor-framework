package com.intentreactor.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.ConfirmationManager;
import com.intentreactor.api.Intent;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.IntentPreprocessor;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Planner;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionStore;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.service.multiintent.LlmDrivenMultiIntentStrategy;
import com.intentreactor.core.service.multiintent.ParallelMultiIntentStrategy;
import com.intentreactor.core.service.multiintent.SequentialMultiIntentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiIntentTest {

    @Mock
    private IntentPreprocessor preprocessor;
    @Mock
    private Planner planner;
    @Mock
    private SessionStore sessionStore;
    @Mock
    private ToolProvider toolProvider;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    private IntentReactorServiceImpl service;
    private IntentReactorProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IntentReactorProperties();
        properties.getPlanning().setAutonomous(true);
        ConfirmationManager confirmationManager = new DefaultConfirmationManager(properties);
        ObjectMapper objectMapper = new ObjectMapper();
        SequentialMultiIntentStrategy sequential = new SequentialMultiIntentStrategy(sessionStore);
        LlmDrivenMultiIntentStrategy llmDriven = new LlmDrivenMultiIntentStrategy(
                sequential, chatClient, properties, objectMapper);
        ParallelMultiIntentStrategy parallel = new ParallelMultiIntentStrategy(
                Executors.newCachedThreadPool(), properties);
        service = new IntentReactorServiceImpl(preprocessor, planner, sessionStore,
                toolProvider, eventPublisher, properties, confirmationManager,
                objectMapper, List.of(sequential, llmDriven, parallel));

        when(sessionStore.findById(anyString())).thenReturn(Optional.empty());
        when(toolProvider.getAvailableTools(any())).thenReturn(List.of());
    }

    @Test
    void executeSequential_processesIntentsInOrder() {
        properties.getPlanning().getMultiIntent().setStrategy("sequential");

        Intent intent1 = new Intent("task1", 0.9, Map.of());
        Intent intent2 = new Intent("task2", 0.7, Map.of());

        IntentAnalysisResult analysisResult = new IntentAnalysisResult();
        analysisResult.setIntents(List.of(intent1, intent2));
        when(preprocessor.analyze(anyString(), any(), any())).thenReturn(analysisResult);

        when(planner.plan(any(), any())).thenReturn(new SimplePlan(List.of(SimplePlanStep.done("done"))));

        ReactorResponse response = service.process("do two things", Map.of());

        assertThat(response.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        // Planner is called once per intent (2 intents → 2 plan() calls)
        verify(planner, times(2)).plan(any(), any());
    }

    @Test
    void executeLlmDriven_callsLlmForOrdering() {
        properties.getPlanning().getMultiIntent().setStrategy("llm-driven");

        Intent intent1 = new Intent("task1", 0.5, Map.of());
        Intent intent2 = new Intent("task2", 0.8, Map.of());

        IntentAnalysisResult analysisResult = new IntentAnalysisResult();
        analysisResult.setIntents(List.of(intent1, intent2));
        when(preprocessor.analyze(anyString(), any(), any())).thenReturn(analysisResult);

        // LLM returns reordered intent names (task2 first)
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call().content()).thenReturn("[\"task2\", \"task1\"]");

        when(planner.plan(any(), any())).thenReturn(new SimplePlan(List.of(SimplePlanStep.done("done"))));

        service.process("do two things", Map.of());

        // Verify LLM was called for ordering
        verify(chatClient, atLeastOnce()).prompt(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void executeParallel_processesIntentsConcurrently() {
        properties.getPlanning().getMultiIntent().setStrategy("parallel");

        Intent intent1 = new Intent("task1", 0.9, Map.of());
        Intent intent2 = new Intent("task2", 0.8, Map.of());

        IntentAnalysisResult analysisResult = new IntentAnalysisResult();
        analysisResult.setIntents(List.of(intent1, intent2));
        when(preprocessor.analyze(anyString(), any(), any())).thenReturn(analysisResult);

        when(planner.plan(any(), any())).thenReturn(new SimplePlan(List.of(SimplePlanStep.done("done"))));

        ReactorResponse response = service.process("do two things in parallel", Map.of());

        assertThat(response.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(response.getFinalText()).contains("task1");
        assertThat(response.getFinalText()).contains("task2");
    }

    @Test
    void executeSequential_stopsOnConfirmation() {
        properties.getPlanning().setAutonomous(false);
        properties.getPlanning().getMultiIntent().setStrategy("sequential");

        Intent intent1 = new Intent("risky-task", 0.9, Map.of());
        Intent intent2 = new Intent("safe-task", 0.7, Map.of());

        IntentAnalysisResult analysisResult = new IntentAnalysisResult();
        analysisResult.setIntents(List.of(intent1, intent2));
        when(preprocessor.analyze(anyString(), any(), any())).thenReturn(analysisResult);

        Action riskyAction = new SimpleAction("risky-tool", Map.of());
        PlanStep riskyStep = SimplePlanStep.act(riskyAction, "Risky action", true);
        when(planner.plan(any(), any())).thenReturn(new SimplePlan(List.of(riskyStep)));

        ReactorResponse response = service.process("do risky then safe", Map.of());

        assertThat(response.getStatus()).isEqualTo(PlanStatus.AWAITING_CONFIRMATION);
        // Only one intent processed before confirmation pause
        verify(planner, times(1)).plan(any(), any());
    }
}
