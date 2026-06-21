package com.intentreactor.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standard immutable implementation of {@link PlanStep}.
 *
 * <p>Use the static factory methods in custom {@link Planner} implementations:
 * <pre>{@code
 * // A tool invocation step
 * SimplePlanStep.act(new SimpleAction("order_lookup", Map.of("orderId", "42")),
 *                    "Looking up order 42", false);
 *
 * // Terminal steps
 * SimplePlanStep.done("Order 42 is in transit, expected delivery 2024-12-20.");
 * SimplePlanStep.fail("Order lookup service is unavailable.");
 *
 * // Reflexion step (used by the Reflexion planner internally)
 * SimplePlanStep.reflect("The previous lookup failed — retrying with a broader query.");
 * }</pre>
 *
 * @see PlanStep
 * @see SimplePlan
 * @see SimpleAction
 */
public class SimplePlanStep implements PlanStep {

    private final StepType type;
    private final Action action;
    private final String description;
    private final boolean requiresConfirmation;

    /**
     * Creates a plan step with explicit fields. Prefer the factory methods for clarity.
     *
     * @param type                 the step type; must not be {@code null}
     * @param action               the tool invocation; {@code null} for non-ACT steps
     * @param description          a human-readable description; must not be {@code null}
     * @param requiresConfirmation {@code true} if the step must be confirmed before execution
     */
    @JsonCreator
    public SimplePlanStep(
            @JsonProperty("type") StepType type,
            @JsonProperty("action") Action action,
            @JsonProperty("description") String description,
            @JsonProperty("requiresConfirmation") boolean requiresConfirmation) {
        this.type = type;
        this.action = action;
        this.description = description;
        this.requiresConfirmation = requiresConfirmation;
    }

    /**
     * Creates an ACT step that invokes a tool.
     *
     * @param action               the tool invocation descriptor; must not be {@code null}
     * @param description          human-readable description of what this step does
     * @param requiresConfirmation {@code true} if user must confirm before execution
     * @return a non-null ACT step
     */
    public static SimplePlanStep act(Action action, String description, boolean requiresConfirmation) {
        return new SimplePlanStep(StepType.ACT, action, description, requiresConfirmation);
    }

    /**
     * Creates a terminal DONE step indicating successful plan completion.
     *
     * @param description the final answer or summary to include in {@link ReactorResponse#getFinalText()}
     * @return a non-null DONE step
     */
    public static SimplePlanStep done(String description) {
        return new SimplePlanStep(StepType.DONE, null, description, false);
    }

    /**
     * Creates a terminal FAIL step indicating the plan could not be completed.
     *
     * @param description the reason for failure, included in {@link ReactorResponse#getFinalText()}
     * @return a non-null FAIL step
     */
    public static SimplePlanStep fail(String description) {
        return new SimplePlanStep(StepType.FAIL, null, description, false);
    }

    /**
     * Creates a REFLECT step used by the Reflexion planner after a failed action.
     *
     * @param description the self-reflection text to append to session history
     * @return a non-null REFLECT step
     */
    public static SimplePlanStep reflect(String description) {
        return new SimplePlanStep(StepType.REFLECT, null, description, false);
    }

    /**
     * Creates a REASON step carrying the LLM's {@code "thought"} field.
     * REASON steps are emitted by {@link com.intentreactor.core.planner.DefaultReACTPlanner}
     * before each ACT/DONE/FAIL step and are surfaced via SSE but not stored in session messages.
     *
     * @param description the thought text from the LLM response
     * @return a non-null REASON step
     */
    public static SimplePlanStep reason(String description) {
        return new SimplePlanStep(StepType.REASON, null, description, false);
    }

    @Override
    @JsonProperty("type")
    public StepType type() {
        return type;
    }

    @Override
    @JsonProperty("action")
    public Action action() {
        return action;
    }

    @Override
    @JsonProperty("description")
    public String description() {
        return description;
    }

    @Override
    @JsonProperty("requiresConfirmation")
    public boolean requiresConfirmation() {
        return requiresConfirmation;
    }
}
