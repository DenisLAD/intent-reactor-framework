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

    /**
     * Runs the single-intent execution loop for the given session.
     *
     * @param session    the session to process; must not be {@code null}
     * @param persistent {@code true} if the session should be saved to the store after execution
     * @return the planning result for this intent; never {@code null}
     */
    ReactorResponse execute(SessionState session, boolean persistent);
}
