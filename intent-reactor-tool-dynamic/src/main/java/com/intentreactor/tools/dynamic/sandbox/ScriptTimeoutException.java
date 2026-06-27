package com.intentreactor.tools.dynamic.sandbox;

/**
 * Thrown by {@link TimeLimitedContextFactory} when a Rhino script exceeds its configured
 * {@code max-execution-time} wall-clock limit.
 */
public class ScriptTimeoutException extends RuntimeException {
    public ScriptTimeoutException(String message) {
        super(message);
    }
}
