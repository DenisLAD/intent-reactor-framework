package com.intentreactor.mcp.server.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.ConfirmationResult;
import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.Collections;
import java.util.Map;

/**
 * Exposes {@link IntentReactorService} as three MCP tools:
 * <ul>
 *   <li>{@code intent_reactor_process} — process a natural-language message</li>
 *   <li>{@code intent_reactor_proceed} — approve/reject a pending confirmation</li>
 *   <li>{@code intent_reactor_session} — inspect session state</li>
 * </ul>
 *
 * <p>Spring AI's {@code ToolCallbackConverterAutoConfiguration} picks up this bean
 * and registers the three tools with the MCP server.
 */
public class PlannerMcpCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(PlannerMcpCallbackProvider.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String PROCESS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "message": {
                  "type": "string",
                  "description": "The natural-language request to process"
                },
                "session_id": {
                  "type": "string",
                  "description": "Optional session ID for a persistent conversation"
                },
                "context": {
                  "type": "object",
                  "description": "Optional key-value context (userId, locale, etc.)"
                }
              },
              "required": ["message"]
            }
            """;

    private static final String PROCEED_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "session_id": {
                  "type": "string",
                  "description": "Session ID from an AWAITING_CONFIRMATION response"
                },
                "approved": {
                  "type": "boolean",
                  "description": "true to approve the pending action, false to reject"
                },
                "modified_parameters": {
                  "type": "object",
                  "description": "Optional parameter overrides (null means use original)"
                }
              },
              "required": ["session_id", "approved"]
            }
            """;

    private static final String SESSION_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "session_id": {
                  "type": "string",
                  "description": "The session ID to inspect"
                }
              },
              "required": ["session_id"]
            }
            """;

    private final IntentReactorService service;
    private final ObjectMapper objectMapper;

    public PlannerMcpCallbackProvider(IntentReactorService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{
                processCallback(),
                proceedCallback(),
                sessionCallback()
        };
    }

    private ToolCallback processCallback() {
        return new SimpleToolCallback(
                "intent_reactor_process",
                "Process a natural-language request through IntentReactor's planning pipeline. " +
                        "Returns status (COMPLETED, FAILED, or AWAITING_CONFIRMATION), " +
                        "session_id, finalText, and confirmationRequest if confirmation is needed.",
                PROCESS_SCHEMA,
                toolInput -> {
                    Map<String, Object> params = parseJson(toolInput);
                    String message = (String) params.get("message");
                    String sessionId = (String) params.get("session_id");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> context = params.containsKey("context")
                            ? (Map<String, Object>) params.get("context") : Collections.emptyMap();

                    ReactorResponse response = sessionId != null
                            ? service.process(sessionId, message)
                            : service.process(message, context);

                    return buildProcessResponse(response);
                }
        );
    }

    private ToolCallback proceedCallback() {
        return new SimpleToolCallback(
                "intent_reactor_proceed",
                "Approve or reject a pending confirmation request in an AWAITING_CONFIRMATION session.",
                PROCEED_SCHEMA,
                toolInput -> {
                    Map<String, Object> params = parseJson(toolInput);
                    String sessionId = (String) params.get("session_id");
                    boolean approved = Boolean.TRUE.equals(params.get("approved"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> modifiedParams = params.containsKey("modified_parameters")
                            ? (Map<String, Object>) params.get("modified_parameters") : null;

                    ConfirmationResult confirmation = approved
                            ? new ConfirmationResult(true, modifiedParams)
                            : ConfirmationResult.reject();

                    ReactorResponse response = service.proceedAfterConfirmation(sessionId, confirmation);
                    return buildProcessResponse(response);
                }
        );
    }

    private ToolCallback sessionCallback() {
        return new SimpleToolCallback(
                "intent_reactor_session",
                "Get the current state of an IntentReactor session.",
                SESSION_SCHEMA,
                toolInput -> {
                    Map<String, Object> params = parseJson(toolInput);
                    String sessionId = (String) params.get("session_id");
                    try {
                        SessionState state = service.getSessionState(sessionId);
                        Map<String, Object> result = Map.of(
                                "sessionId", state.getId(),
                                "status", state.getPlanState() != null
                                        ? state.getPlanState().getStatus().name() : "UNKNOWN",
                                "goal", state.getPlanState() != null && state.getPlanState().getGoalDescription() != null
                                        ? state.getPlanState().getGoalDescription() : "",
                                "messageCount", state.getMessages().size()
                        );
                        return objectMapper.writeValueAsString(result);
                    } catch (IllegalArgumentException e) {
                        return "{\"error\":\"Session not found: " + sessionId + "\"}";
                    } catch (Exception e) {
                        return "{\"error\":\"" + e.getMessage() + "\"}";
                    }
                }
        );
    }

    private String buildProcessResponse(ReactorResponse response) {
        try {
            Map<String, Object> result;
            if (response.getConfirmationRequest() != null) {
                result = Map.of(
                        "status", response.getStatus().name(),
                        "sessionId", response.getSessionId() != null ? response.getSessionId() : "",
                        "confirmationRequest", Map.of(
                                "toolName", response.getConfirmationRequest().getToolName(),
                                "description", response.getConfirmationRequest().getDescription() != null
                                        ? response.getConfirmationRequest().getDescription() : "",
                                "parameters", response.getConfirmationRequest().getParameters() != null
                                        ? response.getConfirmationRequest().getParameters() : Map.of()
                        ),
                        "message", "Action requires confirmation. Call intent_reactor_proceed with session_id and approved=true/false."
                );
            } else {
                result = Map.of(
                        "status", response.getStatus().name(),
                        "sessionId", response.getSessionId() != null ? response.getSessionId() : "",
                        "finalText", response.getFinalText() != null ? response.getFinalText() : ""
                );
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"Failed to serialize response: " + e.getMessage() + "\"}";
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse tool input JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Minimal ToolCallback implementation backed by a lambda.
     */
    private final class SimpleToolCallback implements ToolCallback {

        private final org.springframework.ai.tool.definition.ToolDefinition definition;
        private final java.util.function.Function<String, String> handler;

        SimpleToolCallback(String name, String description, String schema,
                           java.util.function.Function<String, String> handler) {
            this.definition = DefaultToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(schema)
                    .build();
            this.handler = handler;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            try {
                return handler.apply(toolInput);
            } catch (Exception e) {
                log.error("Planner MCP tool '{}' failed: {}", definition.name(), e.getMessage(), e);
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        }
    }
}
