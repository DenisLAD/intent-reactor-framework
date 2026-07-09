package com.intentreactor.strategies.deliberation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Intent;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.SimulatableTool;
import com.intentreactor.api.StepType;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.planner.DefaultReACTPlanner;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.config.StrategySessionKeys;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Uses a real {@link DefaultReACTPlanner} as the delegate (not a mock) so that the [REASON, ACT]
 * two-step plan shape it emits by default is exercised — this is what the {@code steps().get(0)}
 * structural bug in {@link SANDPlanner} used to miss entirely.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SANDPlannerTest {

    @Mock
    private ChatClient delegateChatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec delegateRequestSpec;
    @Mock
    private ChatClient.CallResponseSpec delegateResponseSpec;

    @Mock
    private ChatClient sandChatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec sandRequestSpec;
    @Mock
    private ChatClient.CallResponseSpec sandResponseSpec;

    @Mock
    private ToolProvider toolProvider;
    @Mock
    private SimulatableTool readStackTraceTool;
    @Mock
    private SimulatableTool checkLogsTool;

    private SessionState session;
    private IntentAnalysisResult intent;
    private StrategiesProperties props;
    private IntentReactorProperties intentReactorProperties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        session = new SessionState("sand-test-session");
        objectMapper = new ObjectMapper();
        intentReactorProperties = new IntentReactorProperties();

        Intent i = new Intent("diagnose_error", 1.0, Map.of());
        intent = new IntentAnalysisResult();
        intent.setIntents(List.of(i));

        props = new StrategiesProperties();
        props.getSand().setNumCandidates(2);
        props.getSand().setUseSimulation(true);
        props.getSand().setEvaluationMethod("simulation");

        when(delegateChatClient.prompt(any(Prompt.class))).thenReturn(delegateRequestSpec);
        when(delegateRequestSpec.call()).thenReturn(delegateResponseSpec);

        when(sandChatClient.prompt(any(Prompt.class))).thenReturn(sandRequestSpec);
        when(sandRequestSpec.call()).thenReturn(sandResponseSpec);

        when(readStackTraceTool.getName()).thenReturn("read_stack_trace");
        when(readStackTraceTool.getDescription()).thenReturn("Read the stack trace");
        when(readStackTraceTool.isRisky()).thenReturn(false);

        when(checkLogsTool.getName()).thenReturn("check_logs");
        when(checkLogsTool.getDescription()).thenReturn("Check application logs");
        when(checkLogsTool.isRisky()).thenReturn(false);

        when(toolProvider.getAvailableTools(any(SessionState.class)))
                .thenReturn(List.of(readStackTraceTool, checkLogsTool));
    }

    @Test
    void deliberatesOverRealDelegatePlan_picksBetterCandidateAndPreservesReasoning() {
        when(delegateResponseSpec.content()).thenReturn(
                "{\"thought\": \"Need to check the stack trace first\", " +
                        "\"toolName\": \"read_stack_trace\", \"parameters\": {\"module\": \"payment\"}}");
        when(sandResponseSpec.content()).thenReturn(
                "[{\"toolName\": \"check_logs\", \"parameters\": {\"module\": \"payment\"}, " +
                        "\"reasoning\": \"Check logs for context\"}]");

        when(readStackTraceTool.simulate(any(ToolInput.class))).thenReturn(ToolResult.error("boom"));
        when(checkLogsTool.simulate(any(ToolInput.class))).thenReturn(ToolResult.ok("logs ok"));

        Planner realDelegate = new DefaultReACTPlanner(delegateChatClient, toolProvider, intentReactorProperties, objectMapper);
        SANDPlanner planner = new SANDPlanner(realDelegate, sandChatClient, objectMapper, toolProvider, props, intentReactorProperties);

        Plan result = planner.plan(session, intent);

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).type()).isEqualTo(StepType.REASON);
        assertThat(result.steps().get(0).description()).isEqualTo("Need to check the stack trace first");
        assertThat(result.steps().get(1).type()).isEqualTo(StepType.ACT);
        assertThat(result.steps().get(1).action().toolName()).isEqualTo("check_logs");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trainingLog =
                (List<Map<String, Object>>) session.getAttributes().get(StrategySessionKeys.SAND_TRAINING_LOG);
        assertThat(trainingLog).hasSize(1);
        assertThat(trainingLog.get(0).get("selectedIndex")).isEqualTo(1);
    }

    @Test
    void passesThroughUnchanged_whenDelegatePlanHasNoActStep() {
        Planner mockDelegate = mock(Planner.class);
        Plan noActPlan = new SimplePlan(List.of(SimplePlanStep.fail("no tools")));
        when(mockDelegate.plan(any(), any())).thenReturn(noActPlan);

        SANDPlanner planner = new SANDPlanner(mockDelegate, sandChatClient, objectMapper, toolProvider, props, intentReactorProperties);

        Plan result = planner.plan(session, intent);

        assertThat(result).isSameAs(noActPlan);
        verifyNoInteractions(sandChatClient);
    }

    @Test
    void findsActStepNotAtIndexZero_andKeepsDelegatePlanWhenNoAlternativeWins() {
        Planner mockDelegate = mock(Planner.class);
        Plan reasonThenActPlan = new SimplePlan(List.of(
                SimplePlanStep.reason("thinking"),
                SimplePlanStep.act(new SimpleAction("read_stack_trace", Map.of("module", "payment")),
                        "Execute read_stack_trace", false)));
        when(mockDelegate.plan(any(), any())).thenReturn(reasonThenActPlan);

        when(sandResponseSpec.content()).thenReturn("[]");
        when(readStackTraceTool.simulate(any(ToolInput.class))).thenReturn(ToolResult.ok("stack trace read"));

        SANDPlanner planner = new SANDPlanner(mockDelegate, sandChatClient, objectMapper, toolProvider, props, intentReactorProperties);

        Plan result = planner.plan(session, intent);

        assertThat(result).isSameAs(reasonThenActPlan);
    }
}
