package com.intentreactor.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * A single step in a {@link Plan}, carrying the planner's intent for one reasoning or action unit.
 *
 * <p>Steps are produced by {@link Planner#plan} and consumed by {@code IntentReactorServiceImpl},
 * which dispatches tool calls for {@link StepType#ACT} steps and records all steps as
 * {@link ReasoningStep} entries in the final {@link ReactorResponse}.
 *
 * <p>Use {@link SimplePlanStep} as the standard implementation.
 *
 * @see StepType
 * @see Action
 * @see SimplePlanStep
 */
@JsonDeserialize(as = SimplePlanStep.class)
public interface PlanStep {

    /**
     * Returns the type of this step within the ReACT reasoning cycle.
     *
     * @return the step type; never {@code null}
     */
    StepType type();

    /**
     * Returns the tool invocation descriptor for {@link StepType#ACT} steps;
     * {@code null} for all other step types (REASON, OBSERVE, REFLECT, DONE, FAIL).
     *
     * @return the action, or {@code null} for non-ACT steps
     */
    Action action();

    /**
     * Returns a human-readable description of this step, used as the message content
     * stored in session history and shown in reasoning traces.
     *
     * @return the description; never {@code null}
     */
    String description();

    /**
     * Returns {@code true} if this step requires user confirmation before execution.
     *
     * <p>Set by the planner when the associated tool has {@link Tool#isRisky()} == {@code true}
     * and the framework is in non-autonomous mode.
     *
     * @return {@code true} if confirmation is required
     */
    boolean requiresConfirmation();
}
