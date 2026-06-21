package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * An immutable record of a tool invocation that was actually executed during a planning cycle.
 *
 * <p>Collected in {@link ReactorResponse#getActions()} for every successfully executed
 * {@link StepType#ACT} step. Useful for audit trails, display in UI, and post-processing.
 *
 * @see ReactorResponse
 * @see ToolResult
 */
@Getter
@Setter
public class PerformedAction {

    private String toolName;
    private Map<String, Object> parameters;
    private ToolResult result;

    /**
     * Required by Jackson for deserialization.
     */
    public PerformedAction() {
    }

    /**
     * Creates a record of a completed tool invocation.
     *
     * @param toolName   the name of the tool that was called
     * @param parameters the parameters that were passed to the tool
     * @param result     the result returned by the tool
     */
    public PerformedAction(String toolName, Map<String, Object> parameters, ToolResult result) {
        this.toolName = toolName;
        this.parameters = parameters;
        this.result = result;
    }

}
