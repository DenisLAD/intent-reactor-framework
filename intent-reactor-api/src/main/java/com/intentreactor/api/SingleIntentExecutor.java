package com.intentreactor.api;

/**
 * Callback that executes a single-intent execution cycle on a session.
 *
 * <p>Passed to {@link MultiIntentStrategy#execute} to give multi-intent strategy
 * implementations access to {@code IntentReactorServiceImpl.continueExecution()}
 * without creating a circular dependency.
 */
@FunctionalInterface
public interface SingleIntentExecutor {

    ReactorResponse execute(SessionState session, boolean persistent);
}
