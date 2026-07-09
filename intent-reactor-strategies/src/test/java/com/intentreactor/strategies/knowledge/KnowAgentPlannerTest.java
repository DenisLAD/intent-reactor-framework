package com.intentreactor.strategies.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.strategies.config.StrategiesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the KB-injection duplication bug: {@code injectKbContext()} used to add
 * a new "[ACTION KNOWLEDGE BASE]" SYSTEM message on every {@code plan()} call unconditionally,
 * flooding the session history over a multi-step ReACT run. The fix gates injection behind a
 * content-signature guard — only inject when the computed KB block actually changed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowAgentPlannerTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ToolProvider toolProvider;
    @Mock
    private Tool weatherTool;

    private SessionState session;
    private IntentAnalysisResult intent;
    private StrategiesProperties props;
    private ObjectMapper objectMapper;
    private Planner delegate;

    @BeforeEach
    void setUp() {
        session = new SessionState("s1");
        intent = new IntentAnalysisResult();
        props = new StrategiesProperties();
        objectMapper = new ObjectMapper();

        when(weatherTool.getName()).thenReturn("weather");
        when(weatherTool.getDescription()).thenReturn("Requires validation. Returns data.");
        when(toolProvider.getAvailableTools(any())).thenReturn(List.of(weatherTool));

        delegate = mock(Planner.class);
        when(delegate.plan(any(), any())).thenReturn(new SimplePlan(List.of(SimplePlanStep.done("ok"))));
    }

    private long kbMessageCount(SessionState s) {
        return s.getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.SYSTEM
                        && m.getContent() != null
                        && m.getContent().startsWith("[ACTION KNOWLEDGE BASE]"))
                .count();
    }

    @Test
    void doesNotDuplicateKbMessage_whenStateUnchanged() {
        KnowAgentPlanner planner = new KnowAgentPlanner(delegate, chatClient, toolProvider, objectMapper, props);

        planner.plan(session, intent);
        planner.plan(session, intent);
        planner.plan(session, intent);

        assertThat(kbMessageCount(session)).isEqualTo(1);
    }

    @Test
    void addsNewKbMessage_whenSatisfiedPostconditionsChange() {
        KnowAgentPlanner planner = new KnowAgentPlanner(delegate, chatClient, toolProvider, objectMapper, props);

        planner.plan(session, intent);
        assertThat(kbMessageCount(session)).isEqualTo(1);

        // Tool result satisfies the "validation" precondition via keyword overlap, changing KB content
        // (weather moves from the blocked list to the available list).
        session.addMessage(Message.system("[TOOL_RESULT] weather: some validation data returned"));

        planner.plan(session, intent);
        assertThat(kbMessageCount(session)).isEqualTo(2);
    }
}
