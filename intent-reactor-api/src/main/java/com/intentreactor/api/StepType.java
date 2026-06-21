package com.intentreactor.api;

/**
 * Classifies each step in a {@link Plan} by its role in the ReACT reasoning cycle.
 *
 * <p>The planner returns one step at a time. The execution engine acts on {@link #ACT}
 * steps (invoking a {@link Tool}) and records the rest as reasoning trace entries in
 * {@link ReactorResponse#getReasoningSteps()}.
 *
 * @see PlanStep#type()
 * @see SimplePlanStep
 */
public enum StepType {

    /**
     * The LLM is reasoning about what to do next — no tool is invoked.
     */
    REASON,

    /**
     * A tool should be invoked. The step carries an {@link Action} with tool name and parameters.
     */
    ACT,

    /**
     * The LLM is processing the result of the previous tool invocation.
     */
    OBSERVE,

    /**
     * The LLM is reflecting on past steps. Used exclusively by the Reflexion planner
     * ({@code intent-reactor.planning.strategy=reflexion}), which appends a
     * {@code [REFLECTION]} marker to the session history after each failed action.
     */
    REFLECT,

    /**
     * The plan has reached a successful terminal state.
     * The step's {@link PlanStep#description()} contains the final answer.
     */
    DONE,

    /**
     * The plan has reached a failure terminal state.
     * The step's {@link PlanStep#description()} contains the reason for failure.
     */
    FAIL
}
