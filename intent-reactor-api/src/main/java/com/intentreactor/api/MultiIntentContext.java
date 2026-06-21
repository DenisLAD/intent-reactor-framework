package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestration state for multi-intent request processing.
 *
 * <p>When a user message yields multiple intents and the strategy is {@code sequential}
 * or {@code llm-driven}, the execution engine stores a {@code MultiIntentContext} in
 * {@link SessionState#getAttributes()} under the key {@code "multiIntentState"}.
 * It tracks which intents are pending, which are completed, and collects their responses.
 *
 * @see Intent
 * @see CompositePlan
 * @see SessionState#getAttributes()
 */
@Getter
@Setter
public class MultiIntentContext {

    private List<Intent> pendingIntents;
    private List<Intent> completedIntents = new ArrayList<>();
    private Intent currentIntent;
    private String strategy;
    private Map<String, ReactorResponse> results = new LinkedHashMap<>();

    /**
     * Required by Jackson for deserialization.
     */
    public MultiIntentContext() {
    }

    /**
     * Creates a new context for processing the given intents with the specified strategy.
     *
     * @param intents  the full list of intents to process; must not be {@code null}
     * @param strategy the multi-intent strategy name ({@code sequential}, {@code llm-driven},
     *                 or {@code parallel})
     */
    public MultiIntentContext(List<Intent> intents, String strategy) {
        this.pendingIntents = new ArrayList<>(intents);
        this.strategy = strategy;
    }
}
