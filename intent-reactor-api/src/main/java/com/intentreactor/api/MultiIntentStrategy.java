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

    ReactorResponse execute(SessionState session, MultiIntentContext ctx,
                            boolean persistent, SingleIntentExecutor executor);
}
