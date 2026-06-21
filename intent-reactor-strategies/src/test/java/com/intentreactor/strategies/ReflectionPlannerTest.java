package com.intentreactor.strategies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Intent;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.StepType;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.refinement.ReflectionPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReflectionPlannerTest {

    @Mock
    private Planner delegate;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private SessionState session;
    private IntentAnalysisResult intent;
    private StrategiesProperties props;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        session = new SessionState("test-session");
        props = new StrategiesProperties();
        objectMapper = new ObjectMapper();

        Intent i = new Intent("test_goal", 1.0, Map.of());
        intent = new IntentAnalysisResult();
        intent.setIntents(List.of(i));

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    void reflection_passesThroughNonDoneSteps() {
        Plan actPlan = new SimplePlan(List.of(
                new SimplePlanStep(StepType.ACT, null, "doing something", false)));
        when(delegate.plan(any(), any())).thenReturn(actPlan);

        Planner planner = new ReflectionPlanner(delegate, chatClient, objectMapper, props);
        Plan result = planner.plan(session, intent);

        assertThat(result.steps().get(0).type()).isEqualTo(StepType.ACT);
        verifyNoInteractions(chatClient);
    }

    @Test
    void reflection_evaluatesDoneResponseAndAcceptsHighScore() {
        Plan donePlan = new SimplePlan(List.of(SimplePlanStep.done("Great answer")));
        when(delegate.plan(any(), any())).thenReturn(donePlan);
        when(callResponseSpec.content()).thenReturn(
                "{\"score\": 0.95, \"satisfied\": true, \"critique\": \"\", \"improvement\": \"\"}");

        Planner planner = new ReflectionPlanner(delegate, chatClient, objectMapper, props);
        Plan result = planner.plan(session, intent);

        assertThat(result.steps().get(0).type()).isEqualTo(StepType.DONE);
    }

    @Test
    void reflection_injectsCritiqueAndReplansOnLowScore() {
        Plan donePlan = new SimplePlan(List.of(SimplePlanStep.done("Mediocre answer")));
        Plan improvedPlan = new SimplePlan(List.of(SimplePlanStep.done("Improved answer")));
        when(delegate.plan(any(), any())).thenReturn(donePlan, improvedPlan);
        when(callResponseSpec.content()).thenReturn(
                "{\"score\": 0.4, \"satisfied\": false, \"critique\": \"Too vague\", \"improvement\": \"Add details\"}");

        Planner planner = new ReflectionPlanner(delegate, chatClient, objectMapper, props);
        Plan result = planner.plan(session, intent);

        verify(delegate, times(2)).plan(any(), any());
        assertThat(session.getMessages().stream()
                .anyMatch(m -> m.getContent().contains("CRITIQUE"))).isTrue();
    }

    @Test
    void reflection_respectsMaxIterations() {
        Plan donePlan = new SimplePlan(List.of(SimplePlanStep.done("answer")));
        when(delegate.plan(any(), any())).thenReturn(donePlan);
        when(callResponseSpec.content()).thenReturn(
                "{\"score\": 0.1, \"satisfied\": false, \"critique\": \"Bad\", \"improvement\": \"Fix it\"}");

        props.getReflection().setMaxIterations(2);
        Planner planner = new ReflectionPlanner(delegate, chatClient, objectMapper, props);

        planner.plan(session, intent); // iteration 1
        planner.plan(session, intent); // iteration 2

        // third call: max reached, no more critic
        reset(chatClient);
        planner.plan(session, intent);
        verifyNoInteractions(chatClient);
    }
}
