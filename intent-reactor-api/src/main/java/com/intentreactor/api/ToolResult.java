package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

/**
 * Encapsulates the outcome of a {@link Tool#execute(ToolInput)} call.
 *
 * <p>Use the static factory methods to create results:
 * <pre>{@code
 * // Successful result — data can be any serializable object
 * return ToolResult.ok(Map.of("temperature", "18°C", "condition", "Sunny"));
 *
 * // Failure result — message is shown to the planner as an OBSERVE step
 * return ToolResult.error("City not found: " + cityName);
 * }</pre>
 *
 * <p>The {@code data} field accepts any Java object. The planner converts it to a string
 * using {@code toString()} before embedding it in the LLM prompt. For complex structures,
 * prefer a {@code Map} with meaningful keys or a pre-formatted string.
 *
 * <p>The framework records every {@code ToolResult} in the session history as a
 * {@link Message.Role#SYSTEM} message and fires a {@code PlanStepCompletedEvent}.
 *
 * @see Tool
 * @see ToolInput
 */
@Getter
@Setter
public class ToolResult {

    private boolean success;
    private Object data;
    private String errorMessage;

    /**
     * Required by Jackson for deserialization.
     */
    public ToolResult() {
    }

    /**
     * Creates a result with explicit field values. Prefer {@link #ok(Object)} and
     * {@link #error(String)} for clarity.
     *
     * @param success      whether the tool execution succeeded
     * @param data         the result payload; may be {@code null} for error results
     * @param errorMessage the error description; may be {@code null} for success results
     */
    public ToolResult(boolean success, Object data, String errorMessage) {
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
    }

    /**
     * Creates a successful result carrying the given data.
     *
     * @param data the tool output; may be a primitive, a {@code Map}, a domain object,
     *             or any Jackson-serializable value; may be {@code null}
     * @return a non-null successful {@code ToolResult}
     */
    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null);
    }

    /**
     * Creates a failure result with a human-readable error description.
     *
     * <p>Write the message in a form that helps the planner decide what to do next
     * (e.g., {@code "Order 12345 does not exist"} rather than {@code "NullPointerException"}).
     *
     * @param message the error description; should not be {@code null}
     * @return a non-null failed {@code ToolResult}
     */
    public static ToolResult error(String message) {
        return new ToolResult(false, null, message);
    }

    @Override
    public String toString() {
        return success ? "ToolResult{data=" + data + "}" : "ToolResult{error='" + errorMessage + "'}";
    }
}
