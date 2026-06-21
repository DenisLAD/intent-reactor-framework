package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * A single recognised intent extracted from a user message by {@link IntentPreprocessor}.
 *
 * <p>An {@link IntentAnalysisResult} may contain multiple intents when the user expresses
 * several goals in one message (handled by the multi-intent processing pipeline).
 *
 * @see IntentAnalysisResult
 * @see IntentPreprocessor
 */
@Getter
@Setter
public class Intent {

    private String name;
    private double confidence;
    private Map<String, Object> attributes;

    /**
     * Required by Jackson for deserialization.
     */
    public Intent() {
    }

    /**
     * Creates an intent with name, confidence score, and extracted attributes.
     *
     * @param name       the intent identifier (e.g., {@code "weather_query"})
     * @param confidence a score in the range [0.0, 1.0] indicating extraction certainty
     * @param attributes domain-specific key-value data extracted alongside the intent
     */
    public Intent(String name, double confidence, Map<String, Object> attributes) {
        this.name = name;
        this.confidence = confidence;
        this.attributes = attributes;
    }

}
