package com.intentreactor.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Plan;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.StepType;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultReACTPlannerTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ToolProvider toolProvider;
    @Mock
    private Tool mockTool;

    private DefaultReACTPlanner planner;
    private IntentReactorProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IntentReactorProperties();
        properties.getPlanning().setAutonomous(true);
        planner = new DefaultReACTPlanner(chatClient, toolProvider, properties, new ObjectMapper());

        when(toolProvider.getAvailableTools(any(SessionState.class))).thenReturn(List.of(mockTool));
        when(mockTool.getName()).thenReturn("weather");
        when(mockTool.getDescription()).thenReturn("Get weather");
        when(mockTool.getParameterSchema()).thenReturn(Map.of("type", "object"));
        when(mockTool.isRisky()).thenReturn(false);
    }

    @Test
    void parseResponse_toolCall_returnsActStep() throws Exception {
        String json = "{\"toolName\": \"weather\", \"parameters\": {\"city\": \"Moscow\"}}";
        SessionState session = new SessionState("s1");

        Plan plan = planner.parseResponse(json, List.of(mockTool), session);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.ACT);
        assertThat(plan.steps().get(0).action().toolName()).isEqualTo("weather");
        assertThat(plan.steps().get(0).action().parameters()).containsEntry("city", "Moscow");
    }

    @Test
    void parseResponse_done_returnsDoneStep() throws Exception {
        String json = "{\"done\": true, \"finalMessage\": \"Weather retrieved\"}";
        SessionState session = new SessionState("s2");

        Plan plan = planner.parseResponse(json, List.of(mockTool), session);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.DONE);
        assertThat(plan.isComplete()).isTrue();
    }

    @Test
    void parseResponse_failed_returnsFailStep() throws Exception {
        String json = "{\"failed\": true, \"reason\": \"Cannot proceed\"}";
        SessionState session = new SessionState("s3");

        Plan plan = planner.parseResponse(json, List.of(mockTool), session);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.FAIL);
    }

    @Test
    void parseResponse_multipleJsonObjects_picksReactKey() throws Exception {
        // LLM emits its own JSON first, then the proper ReACT {"done":true,...} at the end
        String response = "{\"entries\":[{\"name\":\"WeatherTool\",\"description\":\"desc\"}]}" +
                "The task is completed. No further action needed." +
                "{\"done\": true, \"finalMessage\": \"List retrieved\"}";
        SessionState session = new SessionState("s5");

        Plan plan = planner.parseResponse(response, List.of(mockTool), session);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.DONE);
    }

    @Test
    void parseResponse_markdownFence_stripped() throws Exception {
        // LLM wraps output in ```json ... ``` — common for chat-tuned models
        String response = "```json\n{\"done\": true, \"finalMessage\": \"Done\"}\n```";
        SessionState session = new SessionState("s6");

        Plan plan = planner.parseResponse(response, List.of(mockTool), session);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.DONE);
    }

    @Test
    void parseResponse_markdownFenceWithBracesInString_parsesCorrectly() throws Exception {
        // LLM wraps in ```json and the parameter value contains Java code with { } braces
        String response = "```json\n" +
                "{\"toolName\": \"weather\", \"parameters\": {\"city\": \"Moscow {UTC+3}\", " +
                "\"note\": \"if (x > 0) { return x; } else { return -x; }\"}}\n" +
                "```";
        SessionState session = new SessionState("s7");

        Plan plan = planner.parseResponse(response, List.of(mockTool), session);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.ACT);
        assertThat(plan.steps().get(0).action().parameters()).containsEntry("city", "Moscow {UTC+3}");
    }

    @Test
    void returnsFailOnLlmException() {
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("LLM unavailable"));

        SessionState session = new SessionState("s4");
        IntentAnalysisResult intent = new IntentAnalysisResult();

        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.FAIL);
    }
}
