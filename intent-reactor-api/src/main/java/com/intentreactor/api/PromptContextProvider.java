package com.intentreactor.api;

import java.util.Map;

/**
 * Extension point for injecting application-specific variables into prompt templates.
 * <p>
 * Declare a Spring {@code @Bean} that implements this interface; the framework
 * will automatically merge the returned map into every system-prompt rendering
 * performed by {@code DefaultReACTPlanner} and {@code LATSPlanner}.
 * <p>
 * Variables are substituted as {@code {key}} placeholders inside the prompt
 * template files (e.g. {@code classpath:prompts/system.md}).
 * <p>
 * Example — inject dynamically computed developer availability:
 * <pre>{@code
 * @Bean
 * public PromptContextProvider teamProvider(TeamRepository repo) {
 *     return session -> Map.of("team", repo.formatAvailability());
 * }
 * }</pre>
 * Then in {@code system.md}:
 * <pre>
 * ## AVAILABLE TEAM
 * {team}
 * </pre>
 */
@FunctionalInterface
public interface PromptContextProvider {

    /**
     * Returns additional {@code {key} → value} pairs to substitute into the prompt.
     * Called once per {@code plan()} invocation.
     *
     * @param session the current session (read-only access to context and attributes)
     * @return a non-null map of extra prompt variables; empty map if none
     */
    Map<String, Object> getAdditionalVariables(SessionState session);
}
