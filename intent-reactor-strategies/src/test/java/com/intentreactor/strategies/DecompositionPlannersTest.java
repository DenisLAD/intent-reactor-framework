package com.intentreactor.strategies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Intent;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.StepType;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.decomposition.LeastToMostPlanner;
import com.intentreactor.strategies.decomposition.PlanAndSolvePlanner;
import com.intentreactor.strategies.decomposition.SelfAskPlanner;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DecompositionPlannersTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;
    @Mock
    private ToolProvider toolProvider;
    @Mock
    private Tool mockTool;

    private SessionState session;
    private IntentAnalysisResult intent;
    private StrategiesProperties props;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        session = new SessionState("test-session");
        session.addMessage(Message.user("find the weather and calculate"));
        props = new StrategiesProperties();
        objectMapper = new ObjectMapper();
        Intent i = new Intent("find_information", 1.0, Map.of());
        intent = new IntentAnalysisResult();
        intent.setIntents(List.of(i));

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(mockTool.getName()).thenReturn("weather");
        when(mockTool.getDescription()).thenReturn("Get weather");
        when(mockTool.getParameterSchema()).thenReturn(Map.of("type", "object", "properties", Map.of()));
        when(toolProvider.getAvailableTools(any())).thenReturn(List.of(mockTool));
    }

    @Test
    void selfAsk_decomposePhaseMovesToAnswer() {
        // Tool-requiring question: DECOMPOSE → ANSWER phase with ACT step
        when(callResponseSpec.content()).thenReturn(
                "[{\"question\": \"What is today's weather?\", \"requires_tool\": true}]");

        SelfAskPlanner planner = new SelfAskPlanner(chatClient, toolProvider, objectMapper, props);
        Plan firstPlan = planner.plan(session, intent);

        assertThat(session.getAttributes()).containsKey("sa_phase");
        assertThat(session.getAttributes().get("sa_phase")).isEqualTo("ANSWER");
        // Tool question returns ACT step
        assertThat(firstPlan.steps().get(0).type()).isEqualTo(StepType.ACT);
    }

    @Test
    void selfAsk_emptyQuestionsGoesToSynthesize() {
        // First plan() call: LLM returns [] → no sub-questions → REASON step returned, phase set to SYNTHESIZE
        when(callResponseSpec.content()).thenReturn("[]", "Final synthesized answer");

        SelfAskPlanner planner = new SelfAskPlanner(chatClient, toolProvider, objectMapper, props);
        Plan firstPlan = planner.plan(session, intent);

        // First call returns REASON so service can publish PlanStepStartedEvent
        assertThat(firstPlan.steps().get(0).type()).isEqualTo(StepType.REASON);
        assertThat(session.getAttributes().get("sa_phase")).isEqualTo("SYNTHESIZE");

        // Second call goes to SYNTHESIZE → DONE
        Plan secondPlan = planner.plan(session, intent);
        assertThat(secondPlan.steps().get(0).type()).isEqualTo(StepType.DONE);
    }

    @Test
    void leastToMost_decomposePhaseStoresTasks() {
        when(callResponseSpec.content()).thenReturn(
                "[{\"id\": 1, \"task\": \"Step one\", \"depends_on\": []}," +
                        " {\"id\": 2, \"task\": \"Step two\", \"depends_on\": [1]}]");

        LeastToMostPlanner planner = new LeastToMostPlanner(chatClient, objectMapper, props);
        planner.plan(session, intent);

        assertThat(session.getAttributes()).containsKey("ltm_tasks");
        assertThat(session.getAttributes().get("ltm_phase")).isEqualTo("SOLVE");
    }

    @Test
    void planAndSolve_planningPhaseStoresSteps() {
        when(callResponseSpec.content()).thenReturn(
                "[{\"toolName\": \"weather\", \"parameters\": {\"city\": \"Moscow\"}, " +
                        "\"description\": \"Get weather\"}]");

        PlanAndSolvePlanner planner = new PlanAndSolvePlanner(chatClient, toolProvider, objectMapper, props);
        Plan plan = planner.plan(session, intent);

        assertThat(session.getAttributes()).containsKey("pas_plan");
        assertThat(session.getAttributes().get("pas_phase")).isEqualTo("EXECUTING");
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.ACT);
    }

    @Test
    void planAndSolve_emptyPlanReturnsFail() {
        when(callResponseSpec.content()).thenReturn("[]");

        PlanAndSolvePlanner planner = new PlanAndSolvePlanner(chatClient, toolProvider, objectMapper, props);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.FAIL);
    }
}
