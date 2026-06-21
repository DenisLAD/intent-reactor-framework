package com.intentreactor.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Standard immutable implementation of {@link Action}.
 *
 * <p>Used by built-in planners and available for custom {@link Planner} implementations:
 * <pre>{@code
 * Action action = new SimpleAction("weather_tool", Map.of("city", "Berlin"));
 * return new SimplePlan(List.of(SimplePlanStep.act(action, "Fetching weather", false)));
 * }</pre>
 *
 * @see Action
 * @see SimplePlanStep
 */
public class SimpleAction implements Action {

    private final String toolName;
    private final Map<String, Object> parameters;

    /**
     * Creates an action targeting the given tool with the given parameters.
     *
     * @param toolName   the name matching {@link Tool#getName()}; must not be {@code null}
     * @param parameters the tool parameters; must not be {@code null}, may be empty
     */
    @JsonCreator
    public SimpleAction(
            @JsonProperty("toolName") String toolName,
            @JsonProperty("parameters") Map<String, Object> parameters) {
        this.toolName = toolName;
        this.parameters = parameters;
    }

    @Override
    @JsonProperty("toolName")
    public String toolName() {
        return toolName;
    }

    @Override
    @JsonProperty("parameters")
    public Map<String, Object> parameters() {
        return parameters;
    }
}
