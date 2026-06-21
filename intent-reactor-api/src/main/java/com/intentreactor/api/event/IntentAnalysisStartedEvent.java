package com.intentreactor.api.event;

/**
 * Published when {@code IntentPreprocessor.analyze()} is about to be called.
 *
 * <p>Carries the raw user message before any LLM processing. Use this event
 * to measure intent analysis latency or to log the incoming request.
 */
public class IntentAnalysisStartedEvent extends IntentReactorEvent {

    private final String message;

    /**
     * Creates the event.
     *
     * @param source    the publishing component
     * @param sessionId the session identifier
     * @param message   the raw user message being analysed
     */
    public IntentAnalysisStartedEvent(Object source, String sessionId, String message) {
        super(source, sessionId);
        this.message = message;
    }

    /**
     * Returns the raw user message being submitted to the intent preprocessor.
     */
    public String getMessage() {
        return message;
    }
}
