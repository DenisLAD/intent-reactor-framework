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
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.decomposition.LeastToMostPlanner;
import com.intentreactor.strategies.decomposition.PlanAndSolvePlanner;
import com.intentreactor.strategies.decomposition.SelfAskPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
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
    private IntentReactorProperties intentReactorProperties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        session = new SessionState("test-session");
        session.addMessage(Message.user("find the weather and calculate"));
        props = new StrategiesProperties();
        intentReactorProperties = new IntentReactorProperties();
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
                "[{\"question\": \"What is today's weather?\", \"requires_tool\": true, \"tool_name\": \"weather\"}]");

        SelfAskPlanner planner = new SelfAskPlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan firstPlan = planner.plan(session, intent);

        assertThat(session.getAttributes()).containsKey("sa_phase");
        assertThat(session.getAttributes().get("sa_phase")).isEqualTo("ANSWER");
        // Tool question returns ACT step
        assertThat(firstPlan.steps().get(0).type()).isEqualTo(StepType.ACT);
        assertThat(firstPlan.steps().get(0).action().toolName()).isEqualTo("weather");
    }

    @Test
    void selfAsk_emptyQuestionsGoesToSynthesize() {
        // First plan() call: LLM returns [] → no sub-questions → REASON step returned, phase set to SYNTHESIZE
        when(callResponseSpec.content()).thenReturn("[]", "Final synthesized answer");

        SelfAskPlanner planner = new SelfAskPlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan firstPlan = planner.plan(session, intent);

        // First call returns REASON so service can publish PlanStepStartedEvent
        assertThat(firstPlan.steps().get(0).type()).isEqualTo(StepType.REASON);
        assertThat(session.getAttributes().get("sa_phase")).isEqualTo("SYNTHESIZE");

        // Second call goes to SYNTHESIZE → DONE
        Plan secondPlan = planner.plan(session, intent);
        assertThat(secondPlan.steps().get(0).type()).isEqualTo(StepType.DONE);
    }

    @Test
    void selfAsk_riskyToolRequiresConfirmation_whenNotAutonomous() {
        when(mockTool.isRisky()).thenReturn(true);
        when(callResponseSpec.content()).thenReturn(
                "[{\"question\": \"What is today's weather?\", \"requires_tool\": true, \"tool_name\": \"weather\"}]");

        SelfAskPlanner planner = new SelfAskPlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).requiresConfirmation()).isTrue();
    }

    @Test
    void selfAsk_riskyToolSkipsConfirmation_whenAutonomous() {
        when(mockTool.isRisky()).thenReturn(true);
        intentReactorProperties.getPlanning().setAutonomous(true);
        when(callResponseSpec.content()).thenReturn(
                "[{\"question\": \"What is today's weather?\", \"requires_tool\": true, \"tool_name\": \"weather\"}]");

        SelfAskPlanner planner = new SelfAskPlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).requiresConfirmation()).isFalse();
    }

    @Test
    void selfAsk_unknownToolNameFallsBackToFirstAvailableTool() {
        when(callResponseSpec.content()).thenReturn(
                "[{\"question\": \"What is today's weather?\", \"requires_tool\": true, \"tool_name\": \"nonexistent_tool\"}]");

        SelfAskPlanner planner = new SelfAskPlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.ACT);
        assertThat(plan.steps().get(0).action().toolName()).isEqualTo("weather");
    }

    @Test
    void leastToMost_decomposePhaseStoresTasks() {
        when(callResponseSpec.content()).thenReturn(
                "[{\"id\": 1, \"task\": \"Step one\", \"depends_on\": []}," +
                        " {\"id\": 2, \"task\": \"Step two\", \"depends_on\": [1]}]",
                "Answer to step one", "Answer to step two");

        LeastToMostPlanner planner = new LeastToMostPlanner(chatClient, objectMapper, props);
        planner.plan(session, intent);

        assertThat(session.getAttributes()).containsKey("ltm_tasks");
        assertThat(session.getAttributes().get("ltm_phase")).isEqualTo("SOLVE");
    }

    @Test
    void leastToMost_solvesRealSubtasksViaLlmAndSynthesizesFromAnswers() {
        when(callResponseSpec.content()).thenReturn(
                "[{\"id\": 1, \"task\": \"Step one\"}, {\"id\": 2, \"task\": \"Step two\"}]",
                "Answer to step one",
                "Answer to step two",
                "Final synthesized answer");

        LeastToMostPlanner planner = new LeastToMostPlanner(chatClient, objectMapper, props);

        // decompose() -> solveNext(index=0): consumes decompose response + solve-task-0 response
        Plan p1 = planner.plan(session, intent);
        assertThat(p1.steps().get(0).type()).isEqualTo(StepType.REASON);

        // solveNext(index=1): consumes solve-task-1 response
        Plan p2 = planner.plan(session, intent);
        assertThat(p2.steps().get(0).type()).isEqualTo(StepType.REASON);

        // index >= tasks.size() -> synthesizeFinal(): consumes synthesize response
        Plan p3 = planner.plan(session, intent);
        assertThat(p3.steps().get(0).type()).isEqualTo(StepType.DONE);
        assertThat(p3.steps().get(0).description()).isEqualTo("Final synthesized answer");

        @SuppressWarnings("unchecked")
        Map<String, String> results = (Map<String, String>) session.getAttributes().get("ltm_results");
        assertThat(results.get("0")).isEqualTo("Answer to step one");
        assertThat(results.get("1")).isEqualTo("Answer to step two");
    }

    @Test
    void leastToMost_emptyDecompositionSynthesizesDirectly() {
        when(callResponseSpec.content()).thenReturn("[]", "Direct answer");

        LeastToMostPlanner planner = new LeastToMostPlanner(chatClient, objectMapper, props);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.DONE);
        assertThat(plan.steps().get(0).description()).isEqualTo("Direct answer");
    }

    @Test
    void planAndSolve_planningPhaseStoresSteps() {
        when(callResponseSpec.content()).thenReturn(
                "[{\"toolName\": \"weather\", \"parameters\": {\"city\": \"Moscow\"}, " +
                        "\"description\": \"Get weather\"}]");

        PlanAndSolvePlanner planner = new PlanAndSolvePlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(session.getAttributes()).containsKey("pas_plan");
        assertThat(session.getAttributes().get("pas_phase")).isEqualTo("EXECUTING");
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.ACT);
    }

    @Test
    void planAndSolve_emptyPlanReturnsFail() {
        when(callResponseSpec.content()).thenReturn("[]");

        PlanAndSolvePlanner planner = new PlanAndSolvePlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.FAIL);
    }

    @Test
    void planAndSolve_riskyToolRequiresConfirmation_whenNotAutonomous() {
        when(mockTool.isRisky()).thenReturn(true);
        when(callResponseSpec.content()).thenReturn(
                "[{\"toolName\": \"weather\", \"parameters\": {\"city\": \"Moscow\"}, \"description\": \"Get weather\"}]");

        PlanAndSolvePlanner planner = new PlanAndSolvePlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).requiresConfirmation()).isTrue();
    }

    @Test
    void planAndSolve_riskyToolSkipsConfirmation_whenAutonomous() {
        when(mockTool.isRisky()).thenReturn(true);
        intentReactorProperties.getPlanning().setAutonomous(true);
        when(callResponseSpec.content()).thenReturn(
                "[{\"toolName\": \"weather\", \"parameters\": {\"city\": \"Moscow\"}, \"description\": \"Get weather\"}]");

        PlanAndSolvePlanner planner = new PlanAndSolvePlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).requiresConfirmation()).isFalse();
    }

    @Test
    void planAndSolve_synthesisIgnoresMessagesFromPreviousTurn() {
        // Simulate a leftover SYSTEM message from an earlier, unrelated planning cycle in the same session.
        session.addMessage(Message.system("[TOOL_RESULT] stale_tool: leftover data from a previous turn"));

        when(callResponseSpec.content()).thenReturn(
                "[{\"toolName\": \"weather\", \"parameters\": {\"city\": \"Moscow\"}, \"description\": \"Get weather\"}]",
                "Synthesized answer");

        PlanAndSolvePlanner planner = new PlanAndSolvePlanner(chatClient, toolProvider, objectMapper, props, intentReactorProperties);
        Plan p1 = planner.plan(session, intent);
        assertThat(p1.steps().get(0).type()).isEqualTo(StepType.ACT);

        // Engine would normally append the tool result as a SYSTEM message before the next plan() call.
        session.addMessage(Message.system("[TOOL_RESULT] weather: 18°C sunny"));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        Plan p2 = planner.plan(session, intent);
        assertThat(p2.steps().get(0).type()).isEqualTo(StepType.DONE);

        verify(chatClient, org.mockito.Mockito.atLeastOnce()).prompt(captor.capture());
        String allPromptText = captor.getAllValues().stream()
                .map(Prompt::getContents)
                .reduce("", String::concat);
        assertThat(allPromptText).doesNotContain("leftover data from a previous turn");
        assertThat(allPromptText).contains("18°C sunny");
    }
}
