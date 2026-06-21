package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The structured output of {@link IntentPreprocessor#analyze}.
 *
 * <p>Passed to {@link Planner#plan} as the second argument. The planner uses the primary
 * intent to formulate the planning goal, and may use entities to parameterise tool calls.
 *
 * @see IntentPreprocessor
 * @see Intent
 * @see Entity
 */
@Getter
@Setter
public class IntentAnalysisResult {

    private List<Intent> intents = new ArrayList<>();
    private List<Entity> entities = new ArrayList<>();
    private boolean uncertain;
    private String reasoningSuggestion;
    private Map<String, Object> rawLLMOutput;

    /**
     * Required by Jackson for deserialization.
     */
    public IntentAnalysisResult() {
    }

    /**
     * Returns {@code true} if at least one intent was extracted.
     *
     * @return {@code true} when {@code intents} is non-null and non-empty
     */
    public boolean hasIntents() {
        return intents != null && !intents.isEmpty();
    }

    /**
     * Returns the intent with the highest confidence score, or the first intent
     * if scores are equal. Returns {@code null} if no intents were extracted.
     *
     * @return the primary intent; may be {@code null}
     */
    public Intent primaryIntent() {
        if (!hasIntents()) return null;
        return intents.stream()
                .max((a, b) -> Double.compare(a.getConfidence(), b.getConfidence()))
                .orElse(intents.get(0));
    }
}
