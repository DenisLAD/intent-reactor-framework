package com.intentreactor.api;

import java.util.Map;

/**
 * The single entry point for all intent-processing operations.
 *
 * <p>{@code IntentReactorService} orchestrates the complete ReACT pipeline:
 * intent analysis via {@link IntentPreprocessor}, plan construction via {@link Planner},
 * and tool execution with event publishing. Callers interact only with this interface.
 *
 * <h2>Stateless (one-shot) processing</h2>
 * <pre>{@code
 * @Autowired
 * IntentReactorService reactor;
 *
 * ReactorResponse response = reactor.process(
 *     "What is the weather in Berlin?",
 *     Map.of("userId", "user-42", "locale", "de")
 * );
 * if (response.getStatus() == PlanStatus.COMPLETED) {
 *     System.out.println(response.getFinalText());
 * }
 * }</pre>
 *
 * <h2>Dialog (session-persistent) processing</h2>
 * <pre>{@code
 * // First turn
 * ReactorResponse r1 = reactor.process("session-abc", "Book a flight to Paris");
 *
 * // Second turn in the same session
 * ReactorResponse r2 = reactor.process("session-abc", "Make it business class");
 * }</pre>
 *
 * <h2>Handling confirmation for risky tools</h2>
 * <pre>{@code
 * ReactorResponse r = reactor.process("session-abc", "Cancel order 42");
 *
 * if (r.getStatus() == PlanStatus.AWAITING_CONFIRMATION) {
 *     ConfirmationRequest req = r.getConfirmationRequest();
 *     boolean approved = ui.askUser(req.getDescription());
 *
 *     ReactorResponse resumed = reactor.proceedAfterConfirmation(
 *         r.getSessionId(),
 *         approved ? ConfirmationResult.approve() : ConfirmationResult.reject()
 *     );
 * }
 * }</pre>
 *
 * <p>The default implementation ({@code IntentReactorServiceImpl}) is auto-registered
 * by {@code IntentReactorAutoConfiguration}. Override it by declaring a
 * {@code @Primary} Spring bean of this type.
 *
 * @see ReactorResponse
 * @see PlanStatus
 * @see ConfirmationResult
 * @see SessionState
 */
public interface IntentReactorService {

    /**
     * Processes a message without maintaining conversational state.
     *
     * <p>A temporary {@link SessionState} is created for the duration of this call
     * and discarded afterwards. Use this for single-turn interactions where dialog
     * history is not required.
     *
     * @param message the natural-language input; must not be {@code null}
     * @param context optional key-value pairs injected into session attributes
     *                (e.g., {@code userId}, {@code locale}); may be {@code null}
     * @return the planning result; never {@code null}
     */
    ReactorResponse process(String message, Map<String, Object> context);

    /**
     * Processes a message within a persistent session, maintaining full dialog history.
     *
     * <p>If no session with {@code sessionId} exists in the configured
     * {@link SessionStore}, a new one is created and persisted. Subsequent calls
     * with the same {@code sessionId} continue the conversation.
     *
     * @param sessionId a stable identifier for the conversation; must not be {@code null}
     * @param message   the natural-language input; must not be {@code null}
     * @return the planning result; never {@code null}
     */
    ReactorResponse process(String sessionId, String message);

    /**
     * Resumes a plan that was paused awaiting user confirmation of a risky action.
     *
     * <p>Must be called after {@link #process} returned a response with
     * {@code status=AWAITING_CONFIRMATION}. If the session's confirmation timeout
     * ({@code intent-reactor.planning.confirmation-timeout}) has elapsed, a
     * {@code FAILED} response is returned.
     *
     * @param sessionId    the session awaiting confirmation; must not be {@code null}
     * @param confirmation the user's decision, optionally with modified parameters;
     *                     must not be {@code null}
     * @return the updated planning result; never {@code null}
     * @throws IllegalArgumentException if no session with {@code sessionId} exists
     * @throws IllegalStateException    if the session is not in {@code AWAITING_CONFIRMATION} state
     */
    ReactorResponse proceedAfterConfirmation(String sessionId, ConfirmationResult confirmation);

    /**
     * Retrieves the current state of a session, including message history and plan progress.
     *
     * @param sessionId the session identifier; must not be {@code null}
     * @return the current session state; never {@code null}
     * @throws IllegalArgumentException if no session with {@code sessionId} exists
     */
    SessionState getSessionState(String sessionId);
}
