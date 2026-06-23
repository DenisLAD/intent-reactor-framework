package com.intentreactor.api;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable context object passed through the {@link MessageContextPostProcessor} pipeline.
 *
 * <p>Carries the messages evicted by the sliding context window (available for compression)
 * and allows processors to annotate individual messages with per-message character limits.
 * The truncation step in {@code DefaultReACTPlanner} reads these overrides via
 * {@link #getCharLimit(Message, int)}, allowing processors to declare larger limits for
 * specific message types without the planner core knowing about any particular tool.
 */
public final class MessageBuildContext {

    private final List<Message> evictedMessages;
    private final SessionState session;
    private final Map<Message, Integer> charLimitOverrides = new IdentityHashMap<>();

    public MessageBuildContext(List<Message> evictedMessages, SessionState session) {
        this.evictedMessages = evictedMessages != null
                ? Collections.unmodifiableList(evictedMessages)
                : List.of();
        this.session = session;
    }

    /**
     * Messages that were pushed out of the context window by the sliding-window step.
     * Non-pinned messages only — pinned messages are always re-inserted into the window.
     */
    public List<Message> getEvictedMessages() {
        return evictedMessages;
    }

    public SessionState getSession() {
        return session;
    }

    /**
     * Registers a per-message character limit override for {@code message}.
     * Used by processors (e.g. snapshot deduplication) to allow certain messages
     * to exceed the global {@code max-message-chars} setting.
     */
    public void setCharLimit(Message message, int limit) {
        charLimitOverrides.put(message, limit);
    }

    /**
     * Returns the effective char limit for {@code message}: the override set by a processor,
     * or {@code defaultLimit} if no override was registered.
     */
    public int getCharLimit(Message message, int defaultLimit) {
        return charLimitOverrides.getOrDefault(message, defaultLimit);
    }
}
