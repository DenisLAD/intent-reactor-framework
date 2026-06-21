package com.intentreactor.api;

import java.util.Map;

/**
 * Analyses a user message and extracts structured intent and entity information.
 *
 * <p>The preprocessor is called once per {@link IntentReactorService#process} invocation,
 * before planning begins. It returns an {@link IntentAnalysisResult} that the
 * {@link Planner} uses to formulate the first plan.
 *
 * <p>The default implementation ({@code DefaultIntentPreprocessor}) sends the message
 * to the configured LLM with a structured JSON prompt and parses the response.
 * Replace it by declaring a {@code @Primary} Spring bean of this type.
 *
 * <h2>Implementing a custom preprocessor</h2>
 * <pre>{@code
 * @Component
 * @Primary
 * public class RuleBasedPreprocessor implements IntentPreprocessor {
 *
 *     @Override
 *     public IntentAnalysisResult analyze(
 *             String message, SessionState session, Map<String, Object> context) {
 *
 *         IntentAnalysisResult result = new IntentAnalysisResult();
 *
 *         if (message.toLowerCase().contains("weather")) {
 *             Intent intent = new Intent("weather_query", 0.95, Map.of());
 *             result.setIntents(List.of(intent));
 *         } else {
 *             result.setUncertain(true);
 *         }
 *         return result;
 *     }
 * }
 * }</pre>
 *
 * @see IntentAnalysisResult
 * @see Intent
 * @see Entity
 */
public interface IntentPreprocessor {

    /**
     * Analyses the user message in the context of the current session and optional metadata.
     *
     * @param message      the raw user input; never {@code null}
     * @param sessionState the current conversation state including message history;
     *                     never {@code null}
     * @param context      additional key-value metadata from the caller
     *                     (e.g., {@code userId}, {@code locale}); may be {@code null}
     * @return a non-null analysis result; {@link IntentAnalysisResult#isUncertain()} may
     * be {@code true} if the intent cannot be determined with confidence
     */
    IntentAnalysisResult analyze(String message, SessionState sessionState, Map<String, Object> context);
}
