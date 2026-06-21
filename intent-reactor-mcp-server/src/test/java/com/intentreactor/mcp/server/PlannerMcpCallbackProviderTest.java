package com.intentreactor.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.ConfirmationRequest;
import com.intentreactor.api.ConfirmationResult;
import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionState;
import com.intentreactor.mcp.server.planner.PlannerMcpCallbackProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerMcpCallbackProviderTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private IntentReactorService mockService;
    private PlannerMcpCallbackProvider provider;

    @BeforeEach
    void setUp() {
        mockService = mock(IntentReactorService.class);
        provider = new PlannerMcpCallbackProvider(mockService, objectMapper);
    }

    private ReactorResponse completedResponse(String sessionId, String finalText) {
        ReactorResponse r = new ReactorResponse();
        r.setSessionId(sessionId);
        r.setStatus(PlanStatus.COMPLETED);
        r.setFinalText(finalText);
        return r;
    }

    private ToolCallback findCallback(String name) {
        for (ToolCallback cb : provider.getToolCallbacks()) {
            if (cb.getToolDefinition().name().equals(name)) return cb;
        }
        throw new AssertionError("No callback named: " + name);
    }

    @Test
    void getToolCallbacks_returnsThreeTools() {
        assertThat(provider.getToolCallbacks()).hasSize(3);
    }

    @Test
    void processCallback_name_isIntentReactorProcess() {
        assertThat(findCallback("intent_reactor_process")).isNotNull();
    }

    @Test
    void processCallback_withoutSessionId_callsStatelessProcess() {
        when(mockService.process(anyString(), any(Map.class)))
                .thenReturn(completedResponse("sess-1", "done"));

        ToolCallback cb = findCallback("intent_reactor_process");
        cb.call("{\"message\":\"hello world\"}");

        verify(mockService).process(eq("hello world"), any(Map.class));
        verify(mockService, never()).process(anyString(), anyString());
    }

    @Test
    void processCallback_withSessionId_callsSessionProcess() {
        when(mockService.process(anyString(), anyString()))
                .thenReturn(completedResponse("sess-abc", "done"));

        ToolCallback cb = findCallback("intent_reactor_process");
        cb.call("{\"message\":\"hello\",\"session_id\":\"sess-abc\"}");

        verify(mockService).process("sess-abc", "hello");
    }

    @Test
    void processCallback_completedStatus_includesStatusAndFinalText() throws Exception {
        when(mockService.process(anyString(), any(Map.class)))
                .thenReturn(completedResponse("s1", "The answer is 42"));

        ToolCallback cb = findCallback("intent_reactor_process");
        String result = cb.call("{\"message\":\"what is the answer?\"}");

        Map<?, ?> parsed = objectMapper.readValue(result, Map.class);
        assertThat(parsed.get("status")).isEqualTo("COMPLETED");
        assertThat(parsed.get("finalText")).isEqualTo("The answer is 42");
    }

    @Test
    void processCallback_awaitingConfirmation_includesConfirmationDetails() throws Exception {
        ReactorResponse response = new ReactorResponse();
        response.setSessionId("sess-confirm");
        response.setStatus(PlanStatus.AWAITING_CONFIRMATION);
        ConfirmationRequest cr = new ConfirmationRequest();
        cr.setToolName("send_email");
        cr.setDescription("Send email to alice");
        cr.setParameters(Map.of("to", "alice@example.com"));
        response.setConfirmationRequest(cr);

        when(mockService.process(anyString(), any(Map.class))).thenReturn(response);

        ToolCallback cb = findCallback("intent_reactor_process");
        String result = cb.call("{\"message\":\"send email\"}");

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(result, Map.class);
        assertThat(parsed.get("status")).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(parsed).containsKey("confirmationRequest");
        assertThat(parsed).containsKey("message");
    }

    @Test
    void proceedCallback_approved_callsProceedWithApprovedResult() {
        when(mockService.proceedAfterConfirmation(anyString(), any(ConfirmationResult.class)))
                .thenReturn(completedResponse("sess-1", "done"));

        ToolCallback cb = findCallback("intent_reactor_proceed");
        cb.call("{\"session_id\":\"sess-1\",\"approved\":true}");

        ArgumentCaptor<ConfirmationResult> captor = ArgumentCaptor.forClass(ConfirmationResult.class);
        verify(mockService).proceedAfterConfirmation(eq("sess-1"), captor.capture());
        assertThat(captor.getValue().isApproved()).isTrue();
    }

    @Test
    void proceedCallback_rejected_callsProceedWithRejectedResult() {
        when(mockService.proceedAfterConfirmation(anyString(), any(ConfirmationResult.class)))
                .thenReturn(completedResponse("sess-1", "rejected"));

        ToolCallback cb = findCallback("intent_reactor_proceed");
        cb.call("{\"session_id\":\"sess-1\",\"approved\":false}");

        ArgumentCaptor<ConfirmationResult> captor = ArgumentCaptor.forClass(ConfirmationResult.class);
        verify(mockService).proceedAfterConfirmation(eq("sess-1"), captor.capture());
        assertThat(captor.getValue().isApproved()).isFalse();
    }

    @Test
    void sessionCallback_returnsSessionInfo() throws Exception {
        SessionState state = new SessionState("sess-info");

        when(mockService.getSessionState("sess-info")).thenReturn(state);

        ToolCallback cb = findCallback("intent_reactor_session");
        String result = cb.call("{\"session_id\":\"sess-info\"}");

        Map<?, ?> parsed = objectMapper.readValue(result, Map.class);
        assertThat(parsed.get("sessionId")).isEqualTo("sess-info");
    }

    @Test
    void sessionCallback_unknownSession_returnsErrorJson() {
        when(mockService.getSessionState(anyString())).thenThrow(new IllegalArgumentException("not found"));

        ToolCallback cb = findCallback("intent_reactor_session");
        String result = cb.call("{\"session_id\":\"unknown\"}");

        assertThat(result).contains("error");
    }
}
