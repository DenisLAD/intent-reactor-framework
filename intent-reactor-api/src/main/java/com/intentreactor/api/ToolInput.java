package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Encapsulates the input passed to {@link Tool#execute(ToolInput)}.
 *
 * <p>Created by the execution engine from the parameters resolved by the planner
 * ({@link Action#parameters()}) and the current session identifier.
 *
 * @see Tool
 * @see ToolResult
 */
@Getter
@Setter
public class ToolInput {

    private Map<String, Object> parameters;
    private String sessionId;

    /**
     * Required by Jackson for deserialization.
     */
    public ToolInput() {
    }

    /**
     * Creates a new input with the given parameters and session context.
     *
     * @param parameters the resolved parameters from the planner's {@link Action}; may be empty
     * @param sessionId  the identifier of the owning session; may be {@code null} for stateless calls
     */
    public ToolInput(Map<String, Object> parameters, String sessionId) {
        this.parameters = parameters;
        this.sessionId = sessionId;
    }

}
