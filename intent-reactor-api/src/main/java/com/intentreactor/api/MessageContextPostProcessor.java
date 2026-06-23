package com.intentreactor.api;

import org.springframework.core.Ordered;

import java.util.List;

/**
 * Extension point for filtering, deduplicating, or transforming the context-window message list
 * <em>after</em> the sliding window has been applied but <em>before</em> the messages are
 * converted to LLM-specific types.
 *
 * <p>Implementations are discovered as Spring beans and invoked in {@link #getOrder()} ascending
 * order. Processors receive a {@link MessageBuildContext} that exposes the messages evicted from
 * the window (for compression use cases) and accepts per-message char-limit overrides (for
 * message-type-specific truncation).
 *
 * <p>Example use cases and recommended order values:
 * <ul>
 *   <li><b>order=0</b>: deduplication of large tool results (e.g. DOM snapshots)</li>
 *   <li><b>order=200</b>: LLM-based compression of evicted messages</li>
 * </ul>
 *
 * @see MessageContextPreProcessor
 * @see MessageBuildContext
 */
public interface MessageContextPostProcessor extends Ordered {

    /**
     * Process the windowed message list.
     *
     * @param messages the messages currently in the context window (after sliding-window step)
     * @param context  mutable context carrying evicted messages and char-limit overrides
     * @return the (potentially modified) message list; must not be {@code null}
     */
    List<Message> process(List<Message> messages, MessageBuildContext context);

    @Override
    default int getOrder() {
        return 0;
    }
}
