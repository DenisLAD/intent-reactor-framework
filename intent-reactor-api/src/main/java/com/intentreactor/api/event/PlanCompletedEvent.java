package com.intentreactor.api.event;

/**
 * Published when a planning cycle reaches a successful terminal step ({@code DONE}).
 *
 * <p>Carries the final text that will be included in
 * {@link com.intentreactor.api.ReactorResponse#getFinalText()}.
 * Use this event to measure end-to-end latency or to record successful plan completions.
 */
public class PlanCompletedEvent extends IntentReactorEvent {

    private final String finalText;

    /**
     * Creates the event.
     *
     * @param source    the publishing component
     * @param sessionId the session identifier
     * @param finalText the natural-language summary from the planner's DONE step
     */
    public PlanCompletedEvent(Object source, String sessionId, String finalText) {
        super(source, sessionId);
        this.finalText = finalText;
    }

    /**
     * Returns the final natural-language answer produced by the planner.
     */
    public String getFinalText() {
        return finalText;
    }
}
