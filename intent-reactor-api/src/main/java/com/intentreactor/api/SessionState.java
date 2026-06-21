package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete mutable state of a single conversation session.
 *
 * <p>{@code SessionState} is the central data structure threaded through all framework
 * components. It is persisted by {@link SessionStore} and restored at the start of each
 * dialog-mode request. Because it must survive serialisation and deserialisation, all
 * fields must be Jackson-compatible.
 *
 * <h2>Contents</h2>
 * <ul>
 *   <li>{@link #getMessages()} — the full dialog history ({@link Message.Role#USER},
 *       {@link Message.Role#ASSISTANT}, and {@link Message.Role#SYSTEM} messages).
 *       SYSTEM messages are tool observation results added by the execution engine.</li>
 *   <li>{@link #getPlanState()} — current planning progress: goal, status,
 *       completed steps, retry count.</li>
 *   <li>{@link #getAttributes()} — a free-form map for cross-cutting data.
 *       Framework-internal keys:
 *       <ul>
 *         <li>{@code "multiIntentState"} — {@link MultiIntentContext} during multi-intent processing</li>
 *         <li>{@code "searchTree"} — LATS planner MCTS tree, persisted across plan iterations</li>
 *         <li>{@code "pendingStep"} — {@link PlanStep} awaiting user confirmation</li>
 *         <li>{@code "pendingModifiedParameters"} — user-modified parameters after a confirmation</li>
 *       </ul>
 *       Application code may store arbitrary serializable values here to share context
 *       between tool calls within the same session.</li>
 * </ul>
 *
 * <h2>Reading session state</h2>
 * <pre>{@code
 * SessionState state = reactor.getSessionState("session-42");
 *
 * state.getMessages().forEach(m ->
 *     System.out.println(m.getRole() + ": " + m.getContent()));
 *
 * System.out.println("Goal: " + state.getPlanState().getGoalDescription());
 * System.out.println("Status: " + state.getPlanState().getStatus());
 *
 * // Access a custom attribute written by application code
 * String userId = (String) state.getAttributes().get("userId");
 * }</pre>
 *
 * <p>Planners are stateless — all continuity across ReACT iterations is maintained
 * exclusively through this object.
 *
 * @see SessionStore
 * @see PlanState
 * @see Message
 */
@Getter
@Setter
public class SessionState {

    private String id;
    @Getter(lombok.AccessLevel.NONE)
    private List<Message> messages = new ArrayList<>();
    private PlanState planState = new PlanState();
    private Map<String, Object> attributes = new HashMap<>();
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Required by Jackson for deserialization.
     */
    public SessionState() {
    }

    /**
     * Creates a new session with the given identifier.
     *
     * @param id the unique session identifier; must not be {@code null}
     */
    public SessionState(String id) {
        this.id = id;
    }

    /**
     * Updates {@code updatedAt} to now. Called by the execution engine after each mutation
     * and by {@link SessionStore} implementations before persisting.
     */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Appends a message to the conversation history and updates {@code updatedAt}.
     *
     * @param message the message to append; must not be {@code null}
     */
    public void addMessage(Message message) {
        this.messages.add(message);
        touch();
    }

    /**
     * Returns the full message history for this session as an unmodifiable view. Use {@link #addMessage} to append.
     */
    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }
}
