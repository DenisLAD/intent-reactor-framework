package com.intentreactor.api.event;

import com.intentreactor.api.ConfirmationRequest;

/**
 * Published when plan execution is paused because a risky tool requires user confirmation.
 *
 * <p>Fired before the {@link com.intentreactor.api.ReactorResponse} with
 * {@code status=AWAITING_CONFIRMATION} is returned to the caller. Use this event
 * to send a push notification, populate a UI confirmation dialog, or start a
 * confirmation timeout timer.
 */
public class ConfirmationRequiredEvent extends IntentReactorEvent {

    private final ConfirmationRequest confirmationRequest;

    /**
     * Creates the event.
     *
     * @param source              the publishing component
     * @param sessionId           the session identifier
     * @param confirmationRequest the details of the action awaiting confirmation;
     *                            must not be {@code null}
     */
    public ConfirmationRequiredEvent(Object source, String sessionId,
                                     ConfirmationRequest confirmationRequest) {
        super(source, sessionId);
        this.confirmationRequest = confirmationRequest;
    }

    /**
     * Returns the confirmation request, including tool name, description,
     * and the parameters the planner intends to pass.
     */
    public ConfirmationRequest getConfirmationRequest() {
        return confirmationRequest;
    }
}
