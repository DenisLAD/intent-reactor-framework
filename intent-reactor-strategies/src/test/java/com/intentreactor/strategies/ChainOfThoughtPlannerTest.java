package com.intentreactor.strategies;

import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.cot.ChainOfThoughtPlanner;
import com.intentreactor.strategies.cot.ZeroShotCoTPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChainOfThoughtPlannerTest {

    @Mock
    private Planner delegate;

    @Mock
    private IntentAnalysisResult intent;

    private SessionState session;
    private StrategiesProperties props;

    @BeforeEach
    void setUp() {
        session = new SessionState("test-session");
        props = new StrategiesProperties();
        when(delegate.plan(any(), any())).thenReturn(
                new SimplePlan(List.of(SimplePlanStep.done("answer"))));
    }

    @Test
    void cot_injectsScaffoldingOnFirstCall() {
        Planner planner = new ChainOfThoughtPlanner(delegate, props);
        planner.plan(session, intent);

        List<Message> messages = session.getMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo(Message.Role.SYSTEM);
        assertThat(messages.get(0).getContent()).contains("CHAIN OF THOUGHT");
    }

    @Test
    void cot_injectsScaffoldingOnlyOnce() {
        Planner planner = new ChainOfThoughtPlanner(delegate, props);
        planner.plan(session, intent);
        planner.plan(session, intent);
        planner.plan(session, intent);

        long systemMessages = session.getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.SYSTEM
                        && m.getContent().contains("CHAIN OF THOUGHT"))
                .count();
        assertThat(systemMessages).isEqualTo(1);
    }

    @Test
    void cot_delegatesPlanToBase() {
        Planner planner = new ChainOfThoughtPlanner(delegate, props);
        Plan result = planner.plan(session, intent);

        verify(delegate, times(1)).plan(session, intent);
        assertThat(result.steps().get(0).description()).isEqualTo("answer");
    }

    @Test
    void zeroShotCoT_injectsReasoningTrigger() {
        Planner planner = new ZeroShotCoTPlanner(delegate, props);
        planner.plan(session, intent);

        assertThat(session.getMessages()).hasSize(1);
        assertThat(session.getMessages().get(0).getContent()).contains("ZERO-SHOT COT");
    }

    @Test
    void zeroShotCoT_injectsOnlyOnce() {
        Planner planner = new ZeroShotCoTPlanner(delegate, props);
        planner.plan(session, intent);
        planner.plan(session, intent);

        long injected = session.getMessages().stream()
                .filter(m -> m.getContent().contains("ZERO-SHOT COT"))
                .count();
        assertThat(injected).isEqualTo(1);
    }

    @Test
    void zeroShotCoT_delegatesToBase() {
        Planner planner = new ZeroShotCoTPlanner(delegate, props);
        Plan result = planner.plan(session, intent);

        verify(delegate, times(1)).plan(session, intent);
        assertThat(result.steps()).isNotEmpty();
    }
}
