package com.intentreactor.api.event;

/**
 * Published when a planning cycle reaches a failure terminal step ({@code FAIL})
 * or when the maximum step limit is exceeded.
 *
 * <p>Use this event to trigger alerts, increment failure metrics, or log the
 * session state for post-mortem analysis.
 */
public class PlanFailedEvent extends IntentReactorEvent {

    private final String reason;

    /**
     * Creates the event.
     *
     * @param source    the publishing component
     * @param sessionId the session identifier
     * @param reason    the human-readable reason for the planning failure
     */
    public PlanFailedEvent(Object source, String sessionId, String reason) {
        super(source, sessionId);
        this.reason = reason;
    }

    /**
     * Returns the reason the plan failed.
     * This is the same text included in {@link com.intentreactor.api.ReactorResponse#getFinalText()}.
     */
    public String getReason() {
        return reason;
    }
}
