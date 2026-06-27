package com.intentreactor.api;

/**
 * Extension point for multi-intent execution strategies.
 *
 * <p>Each strategy receives the current session, the orchestration context,
 * a persistence flag, and a {@link SingleIntentExecutor} callback that drives
 * the single-intent execution loop. This removes the dependency on
 * {@code IntentReactorServiceImpl} internals.
 *
 * <p>Built-in strategies: {@code sequential}, {@code llm-driven}, {@code parallel}.
 * Register additional strategies as Spring beans — the service collects all
 * {@code MultiIntentStrategy} beans and dispatches by {@link #name()}.
 */
public interface MultiIntentStrategy {

    /**
     * The configuration key that identifies this strategy in
     * {@code intent-reactor.planning.multi-intent.strategy}.
     */
    String name();

    /**
     * Executes all intents in {@code ctx} and returns a combined response.
     *
     * @param session    the current session state; must not be {@code null}
     * @param ctx        the multi-intent orchestration context carrying pending intents and results
     * @param persistent {@code true} if session state should be saved between intent executions
     * @param executor   callback that drives the single-intent execution loop without exposing internals
     * @return a non-null combined {@link ReactorResponse} for all processed intents
     */
    ReactorResponse execute(SessionState session, MultiIntentContext ctx,
                            boolean persistent, SingleIntentExecutor executor);
}
