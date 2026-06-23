package com.intentreactor.api;

import org.springframework.core.Ordered;

import java.util.List;

/**
 * Extension point for filtering or enriching the full session message list
 * <em>before</em> the sliding context window is applied.
 *
 * <p>Implementations are discovered as Spring beans and invoked in {@link #getOrder()} ascending
 * order. A pre-processor may remove, reorder, or inject messages into the session-level list
 * before the planner narrows it to the configured window.
 *
 * <p>Example use cases:
 * <ul>
 *   <li>Injecting a preamble or warm-up message at the start of each window</li>
 *   <li>Globally filtering out certain message categories</li>
 * </ul>
 *
 * @see MessageContextPostProcessor
 * @see MessageBuildContext
 */
public interface MessageContextPreProcessor extends Ordered {

    /**
     * Process the full session message list before windowing.
     *
     * @param allMessages the complete list of messages in the session
     * @param session     the current session state
     * @return the (potentially modified) message list; must not be {@code null}
     */
    List<Message> process(List<Message> allMessages, SessionState session);

    @Override
    default int getOrder() {
        return 0;
    }
}
