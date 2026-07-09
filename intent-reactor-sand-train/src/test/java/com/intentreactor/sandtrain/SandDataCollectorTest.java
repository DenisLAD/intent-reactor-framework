package com.intentreactor.sandtrain;

import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.StepType;
import com.intentreactor.api.ToolResult;
import com.intentreactor.api.event.PlanStepCompletedEvent;
import com.intentreactor.strategies.config.StrategySessionKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SandDataCollectorTest {

    @Mock
    private SessionStore sessionStore;

    private SessionState session;
    private SandDataCollector collector;

    @BeforeEach
    void setUp() {
        session = new SessionState("s1");
        collector = new SandDataCollector(sessionStore);
    }

    private PlanStepCompletedEvent actEvent() {
        var step = SimplePlanStep.act(new SimpleAction("read_stack_trace", Map.of()), "Execute", false);
        return new PlanStepCompletedEvent(this, "s1", step, ToolResult.ok("done"));
    }

    private Map<String, Object> entry(int stepIndex, int selectedIndex) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("stepIndex", stepIndex);
        e.put("goal", "diagnose");
        e.put("contextSummary", "ctx");
        e.put("selectedIndex", selectedIndex);
        e.put("candidates", List.of());
        return e;
    }

    @Test
    void ignoresNonActSteps() {
        var reasonStep = SimplePlanStep.reason("thinking");
        collector.onStepCompleted(new PlanStepCompletedEvent(this, "s1", reasonStep, ToolResult.ok("x")));

        verifyNoInteractions(sessionStore);
        assertThat(collector.getTotalRecords()).isZero();
    }

    @Test
    void incrementallyParsesNewEntries_onlyProcessingNewOnes() {
        List<Map<String, Object>> log = new ArrayList<>(List.of(entry(0, 0)));
        session.getAttributes().put(StrategySessionKeys.SAND_TRAINING_LOG, log);
        when(sessionStore.findById("s1")).thenReturn(Optional.of(session));

        collector.onStepCompleted(actEvent());
        assertThat(collector.getTotalRecords()).isEqualTo(1);
        assertThat(collector.getSessionCount()).isEqualTo(1);

        log.add(entry(1, 1));
        collector.onStepCompleted(actEvent());
        assertThat(collector.getTotalRecords()).isEqualTo(2);
        assertThat(collector.getSessionCount()).isEqualTo(1);
    }

    @Test
    void defensive_whenAttributeMissing() {
        when(sessionStore.findById("s1")).thenReturn(Optional.of(session));

        collector.onStepCompleted(actEvent());

        assertThat(collector.getTotalRecords()).isZero();
    }

    @Test
    void defensive_whenAttributeWrongType() {
        session.getAttributes().put(StrategySessionKeys.SAND_TRAINING_LOG, "not-a-list");
        when(sessionStore.findById("s1")).thenReturn(Optional.of(session));

        collector.onStepCompleted(actEvent());

        assertThat(collector.getTotalRecords()).isZero();
    }

    @Test
    void defensive_whenEntryNotAMap() {
        session.getAttributes().put(StrategySessionKeys.SAND_TRAINING_LOG, new ArrayList<>(List.of("garbage")));
        when(sessionStore.findById("s1")).thenReturn(Optional.of(session));

        collector.onStepCompleted(actEvent());

        assertThat(collector.getTotalRecords()).isZero();
    }

    @Test
    void noOp_whenSessionNotFound() {
        when(sessionStore.findById("s1")).thenReturn(Optional.empty());

        collector.onStepCompleted(actEvent());

        assertThat(collector.getTotalRecords()).isZero();
    }
}
