package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * The user's response to a {@link ConfirmationRequest}.
 *
 * <p>Pass to {@link IntentReactorService#proceedAfterConfirmation} after the user has
 * reviewed a pending risky action. Use the factory methods for the common cases:
 * <pre>{@code
 * // Simple approval
 * reactor.proceedAfterConfirmation(sessionId, ConfirmationResult.approve());
 *
 * // Rejection
 * reactor.proceedAfterConfirmation(sessionId, ConfirmationResult.reject());
 *
 * // Approval with modified parameters (e.g., user changed the quantity)
 * reactor.proceedAfterConfirmation(sessionId,
 *     new ConfirmationResult(true, Map.of("quantity", 5)));
 * }</pre>
 *
 * @see ConfirmationRequest
 * @see IntentReactorService#proceedAfterConfirmation
 */
@Getter
@Setter
public class ConfirmationResult {

    private boolean approved;
    private Map<String, Object> modifiedParameters;

    /**
     * Required by Jackson for deserialization.
     */
    public ConfirmationResult() {
    }

    /**
     * Creates a result with the given approval decision and no parameter modifications.
     *
     * @param approved {@code true} if the user approved the action
     */
    public ConfirmationResult(boolean approved) {
        this.approved = approved;
    }

    /**
     * Creates a result with approval decision and optional modified parameters.
     *
     * @param approved           {@code true} if the user approved the action
     * @param modifiedParameters parameters to replace the planner's original values;
     *                           {@code null} means use the original parameters unchanged
     */
    public ConfirmationResult(boolean approved, Map<String, Object> modifiedParameters) {
        this.approved = approved;
        this.modifiedParameters = modifiedParameters;
    }

    /**
     * Creates an approval result with no parameter modifications.
     *
     * @return a non-null approved {@code ConfirmationResult}
     */
    public static ConfirmationResult approve() {
        return new ConfirmationResult(true);
    }

    /**
     * Creates a rejection result. The pending plan step is skipped and the planner
     * is asked to continue without executing the risky tool.
     *
     * @return a non-null rejected {@code ConfirmationResult}
     */
    public static ConfirmationResult reject() {
        return new ConfirmationResult(false);
    }

}
