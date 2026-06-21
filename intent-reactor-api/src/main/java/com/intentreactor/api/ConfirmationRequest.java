package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * A prompt presented to the user when execution is paused awaiting confirmation of a risky action.
 *
 * <p>Included in a {@link ReactorResponse} with {@code status=AWAITING_CONFIRMATION}.
 * The caller displays {@link #getDescription()} to the user and then calls
 * {@link IntentReactorService#proceedAfterConfirmation} with a {@link ConfirmationResult}.
 *
 * <p>Created by {@link ConfirmationManager#buildRequest(PlanStep)}.
 *
 * @see ReactorResponse#getConfirmationRequest()
 * @see ConfirmationResult
 */
@Getter
@Setter
public class ConfirmationRequest {

    private String actionId;
    private String toolName;
    private String description;
    private Map<String, Object> parameters;

    /**
     * Required by Jackson for deserialization.
     */
    public ConfirmationRequest() {
    }

    /**
     * Creates a new confirmation request.
     *
     * @param actionId    a unique identifier for this pending action, used to correlate the response
     * @param toolName    the name of the tool awaiting confirmation
     * @param description a human-readable explanation of what the tool will do
     * @param parameters  the parameters the planner intends to pass to the tool
     */
    public ConfirmationRequest(String actionId, String toolName,
                               String description, Map<String, Object> parameters) {
        this.actionId = actionId;
        this.toolName = toolName;
        this.description = description;
        this.parameters = parameters;
    }

}
