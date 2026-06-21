package com.intentreactor.api.event;

import com.intentreactor.api.IntentAnalysisResult;

/**
 * Published when {@code IntentPreprocessor.analyze()} has returned a result.
 *
 * <p>Carries the full {@link IntentAnalysisResult}, including all recognised intents,
 * extracted entities, and uncertainty flag. Use this event to log intent detection
 * outcomes or to trigger additional processing based on the identified intents.
 */
public class IntentAnalysisCompletedEvent extends IntentReactorEvent {

    private final IntentAnalysisResult result;

    /**
     * Creates the event.
     *
     * @param source    the publishing component
     * @param sessionId the session identifier
     * @param result    the intent analysis result; must not be {@code null}
     */
    public IntentAnalysisCompletedEvent(Object source, String sessionId, IntentAnalysisResult result) {
        super(source, sessionId);
        this.result = result;
    }

    /**
     * Returns the result of intent analysis, including all detected intents and entities.
     */
    public IntentAnalysisResult getResult() {
        return result;
    }
}
