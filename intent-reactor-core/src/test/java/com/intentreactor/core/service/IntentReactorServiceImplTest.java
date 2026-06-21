package com.intentreactor.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.ConfirmationManager;
import com.intentreactor.api.ConfirmationResult;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.IntentPreprocessor;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Planner;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntentReactorServiceImplTest {

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
    @Mock
    private ChatClient chatClient;
    @Mock
    private Tool echoTool;

    private IntentReactorServiceImpl service;
    private IntentReactorProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IntentReactorProperties();
        properties.getPlanning().setAutonomous(true);
        ConfirmationManager confirmationManager =
                new DefaultConfirmationManager(properties);
        service = new IntentReactorServiceImpl(preprocessor, planner, sessionStore,
                toolProvider, eventPublisher, properties, confirmationManager,
                chatClient, new ObjectMapper());
    }

    @Test
    void processStatelessReturnsCompletedResponse() {
        IntentAnalysisResult intent = new IntentAnalysisResult();
        intent.setReasoningSuggestion("echo hello");
        when(preprocessor.analyze(anyString(), any(), any())).thenReturn(intent);

        Action action = new SimpleAction("echo", Map.of("message", "hello"));
        PlanStep actStep = SimplePlanStep.act(action, "echo", false);
        PlanStep doneStep = SimplePlanStep.done("Done: echo hello");

        when(planner.plan(any(SessionState.class), any(IntentAnalysisResult.class)))
                .thenReturn(new SimplePlan(List.of(actStep)))
                .thenReturn(new SimplePlan(List.of(doneStep)));

        when(toolProvider.getAvailableTools(any(SessionState.class))).thenReturn(List.of(echoTool));
        when(echoTool.getName()).thenReturn("echo");
        when(echoTool.execute(any(ToolInput.class))).thenReturn(ToolResult.ok("Echo: hello"));

        ReactorResponse response = service.process("say hello", Map.of());

        assertThat(response.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getToolName()).isEqualTo("echo");
    }

    @Test
    void processWithSessionPersistsState() {
        when(sessionStore.findById("sess-1")).thenReturn(Optional.empty());

        IntentAnalysisResult intent = new IntentAnalysisResult();
        intent.setReasoningSuggestion("test");
        when(preprocessor.analyze(anyString(), any(), any())).thenReturn(intent);
        when(planner.plan(any(), any())).thenReturn(new SimplePlan(List.of(SimplePlanStep.done("Done"))));

        ReactorResponse response = service.process("sess-1", "hello");

        assertThat(response.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        verify(sessionStore, atLeastOnce()).save(any(SessionState.class));
    }

    @Test
    void riskyToolRequiresConfirmationWhenNotAutonomous() {
        properties.getPlanning().setAutonomous(false);

        IntentAnalysisResult intent = new IntentAnalysisResult();
        intent.setReasoningSuggestion("book flight");
        when(preprocessor.analyze(anyString(), any(), any())).thenReturn(intent);

        Action action = new SimpleAction("booking", Map.of("flight", "SU123"));
        PlanStep riskyStep = SimplePlanStep.act(action, "Book flight SU123", true);
        when(planner.plan(any(), any())).thenReturn(new SimplePlan(List.of(riskyStep)));
        when(toolProvider.getAvailableTools(any())).thenReturn(List.of());

        ReactorResponse response = service.process("book a flight", Map.of());

        assertThat(response.isRequiresConfirmation()).isTrue();
        assertThat(response.getStatus()).isEqualTo(PlanStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void proceedAfterRejectionReturnsFailed() {
        when(sessionStore.findById("sess-2")).thenReturn(Optional.of(sessionWithAwaitingConfirmation("sess-2")));

        ReactorResponse response = service.proceedAfterConfirmation("sess-2", ConfirmationResult.reject());

        assertThat(response.getStatus()).isEqualTo(PlanStatus.FAILED);
    }

    private SessionState sessionWithAwaitingConfirmation(String id) {
        SessionState session = new SessionState(id);
        session.getPlanState().setStatus(PlanStatus.AWAITING_CONFIRMATION);
        return session;
    }
}
