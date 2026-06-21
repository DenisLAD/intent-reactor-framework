package com.intentreactor.api;

/**
 * Determines whether a tool invocation requires user confirmation and builds
 * the prompt shown to the user.
 *
 * <p>The default implementation ({@code DefaultConfirmationManager}) requires
 * confirmation when {@link Tool#isRisky()} == {@code true} and
 * {@code intent-reactor.planning.autonomous=false}.
 *
 * <p>Replace with a {@code @Primary} bean to implement finer-grained policies
 * (e.g., confirmation only for amounts above a threshold, or based on user role).
 *
 * @see Tool#isRisky()
 * @see ConfirmationRequest
 * @see IntentReactorService#proceedAfterConfirmation
 */
public interface ConfirmationManager {

    /**
     * Returns {@code true} if the given tool must be confirmed before execution.
     *
     * @param tool the tool the planner intends to invoke; never {@code null}
     * @return {@code true} if user confirmation is required
     */
    boolean needsConfirmation(Tool tool);

    /**
     * Builds a {@link ConfirmationRequest} to present to the user for the given step.
     *
     * @param step the ACT step awaiting confirmation; never {@code null}
     * @return a non-null request containing the action ID, tool name, and human-readable description
     */
    ConfirmationRequest buildRequest(PlanStep step);
}
