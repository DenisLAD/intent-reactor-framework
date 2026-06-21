package com.intentreactor.api;

/**
 * Represents the overall status of a planning cycle within a session.
 *
 * <p>Check {@link ReactorResponse#getStatus()} after calling
 * {@link IntentReactorService#process} to determine the outcome.
 *
 * @see ReactorResponse
 * @see PlanState
 */
public enum PlanStatus {

    /**
     * The plan is executing — intermediate state visible in {@link SessionState#getPlanState()}.
     */
    RUNNING,

    /**
     * The plan reached a successful terminal step ({@link StepType#DONE}).
     */
    COMPLETED,

    /**
     * The plan reached a failure terminal step ({@link StepType#FAIL}) or exceeded retry limits.
     */
    FAILED,

    /**
     * Execution is paused because the next action requires user confirmation.
     * Call {@link IntentReactorService#proceedAfterConfirmation} to resume.
     *
     * <p>Only occurs when {@code intent-reactor.planning.autonomous=false}
     * and the pending {@link Tool#isRisky()} returns {@code true}.
     */
    AWAITING_CONFIRMATION
}
