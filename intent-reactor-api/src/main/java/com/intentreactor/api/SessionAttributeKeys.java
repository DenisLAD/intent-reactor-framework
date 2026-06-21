package com.intentreactor.api;

/**
 * Framework-level session attribute key constants shared between the {@code api} and {@code core} modules.
 *
 * <p>Application-specific constants (stopped, paused, kb_context_enabled, etc.) live in their
 * respective modules (e.g. {@code SessionAttributeKeys} in {@code intent-reactor-ui-testing}).
 */
public final class SessionAttributeKeys {

    /**
     * When set to {@code Boolean.TRUE} in session attributes <em>before</em> calling
     * {@link IntentReactorService#process(String, String)}, signals that the next user message
     * added by {@code process()} must be marked as {@link Message#isPinned() pinned}.
     *
     * <p>Set by {@code UiTestingController} when the user sends a correction message after a
     * PAUSED session. Consumed (via {@code remove()}) and never persisted past a single call.
     */
    public static final String PIN_NEXT_USER_MESSAGE = "_pinNextUserMessage";

    private SessionAttributeKeys() {}
}
