package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A single entry in a conversation's message history.
 *
 * <p>Messages are stored in {@link SessionState#getMessages()} and represent the full
 * dialog between user, assistant, and environment (tool results).
 *
 * <p>Use the static factory methods to create messages:
 * <pre>{@code
 * session.addMessage(Message.user("What is the weather in Paris?"));
 * session.addMessage(Message.assistant("{\"toolName\": \"weather_tool\", ...}"));
 * session.addMessage(Message.system("[TOOL_RESULT] weather_tool: {\"temp\": \"15°C\"}"));
 * }</pre>
 *
 * @see SessionState
 * @see Role
 */
@Getter
@Setter
public class Message {

    private Role role;
    private String content;
    private LocalDateTime timestamp;
    private boolean pinned = false;

    /**
     * Required by Jackson for deserialization.
     */
    public Message() {
    }

    /**
     * Creates a message with explicit fields.
     *
     * @param role      the author role; must not be {@code null}
     * @param content   the message text; must not be {@code null}
     * @param timestamp the creation time; must not be {@code null}
     */
    public Message(Role role, String content, LocalDateTime timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    /**
     * Creates a user message timestamped now.
     */
    public static Message user(String content) {
        return new Message(Role.USER, content, LocalDateTime.now());
    }

    /**
     * Creates an assistant message timestamped now.
     */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, LocalDateTime.now());
    }

    /**
     * Creates a system (tool result / observation) message timestamped now.
     */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, LocalDateTime.now());
    }

    /**
     * Creates a user message that is pinned — never evicted by the sliding context window
     * and excluded from LLM compression. Use for the initial user goal and correction messages
     * sent after a pause.
     */
    public static Message pinnedUser(String content) {
        Message m = new Message(Role.USER, content, LocalDateTime.now());
        m.setPinned(true);
        return m;
    }

    @Override
    public String toString() {
        return "[" + role + "] " + content;
    }

    /**
     * The role of the message author within the conversation.
     *
     * <p><strong>Important:</strong> {@link #SYSTEM} messages are used internally by the framework
     * to store tool results. When building the LLM prompt, {@code DefaultReACTPlanner} converts
     * SYSTEM messages to {@code UserMessage} (not {@code SystemMessage}) because most LLMs
     * expect environmental observations in the user turn.
     */
    public enum Role {
        /**
         * A message from the end user.
         */
        USER,
        /**
         * A response from the LLM.
         */
        ASSISTANT,
        /**
         * An observation from the environment (tool result). Stored as SYSTEM in session history
         * but sent to the LLM as a user-role message to comply with the chat format requirements.
         */
        SYSTEM
    }
}
