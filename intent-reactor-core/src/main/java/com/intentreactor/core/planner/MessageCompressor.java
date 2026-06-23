package com.intentreactor.core.planner;

import com.intentreactor.api.Message;
import com.intentreactor.api.MessageBuildContext;
import com.intentreactor.api.MessageContextPostProcessor;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.event.ContextCompressedEvent;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compresses old session messages into a concise LLM-generated summary and injects it
 * at the head of the context window as a {@code MessageContextPostProcessor}.
 *
 * <p>Fires when the estimated token count of the windowed messages exceeds
 * {@code triggerRatio * maxTokens}. The summary is cached in
 * {@code session.attributes[_contextSummary]} keyed by the evicted-message count so that
 * repeated {@code plan()} calls within the same session avoid redundant LLM calls.
 *
 * <p>On a fresh compression (not from cache), a {@link ContextCompressedEvent} is published
 * so SSE bridges and audit listeners can surface the event to the user.
 */
public class MessageCompressor implements MessageContextPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageCompressor.class);

    static final String ATTR_SUMMARY = "_contextSummary";
    static final String ATTR_SUMMARY_UP_TO = "_contextSummaryUpTo";

    private final ChatClient chatClient;
    private final IntentReactorProperties properties;
    private final PromptLoader promptLoader;
    private final ApplicationEventPublisher eventPublisher;

    public MessageCompressor(ChatClient chatClient,
                             IntentReactorProperties properties,
                             PromptLoader promptLoader,
                             ApplicationEventPublisher eventPublisher) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.promptLoader = promptLoader;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Runs after snapshot deduplication (order 0). Injects an LLM-generated summary of evicted
     * messages at position 0 of the window list if estimated tokens exceed the threshold.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public List<Message> process(List<Message> messages, MessageBuildContext context) {
        IntentReactorProperties.CompressionConfig comp =
                properties.getPlanning().getContextWindow().getCompression();
        if (!comp.isEnabled()) return messages;

        List<Message> evicted = context.getEvictedMessages();
        if (evicted.isEmpty()) return messages;

        int estimated = estimateTokens(messages);
        int threshold = (int) (comp.getMaxTokens() * comp.getTriggerRatio());
        if (estimated <= threshold) return messages;

        String summary = compress(evicted, context.getSession());
        if (summary.isBlank()) return messages;

        List<Message> result = new ArrayList<>(messages.size() + 1);
        result.add(Message.user("[ИСТОРИЯ ДИАЛОГА]\n" + summary));
        result.addAll(messages);
        log.debug("[MessageCompressor] compression triggered: estimated={} tokens (threshold={}), compressed {} evicted messages",
                estimated, threshold, evicted.size());
        return result;
    }

    int estimateTokens(List<Message> messages) {
        int charsPerToken = properties.getPlanning().getContextWindow().getCompression().getCharsPerToken();
        int totalChars = messages.stream()
                .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length())
                .sum();
        return totalChars / Math.max(1, charsPerToken);
    }

    /**
     * Compresses {@code oldMessages} into a single summary string via LLM.
     *
     * <p>Result is cached in {@code session.attributes[_contextSummary]} keyed by
     * {@code _contextSummaryUpTo = oldMessages.size()}.
     */
    String compress(List<Message> oldMessages, SessionState session) {
        int currentOldSize = oldMessages.size();

        Object cached = session.getAttributes().get(ATTR_SUMMARY);
        Object cachedUpTo = session.getAttributes().get(ATTR_SUMMARY_UP_TO);
        if (cached instanceof String summary && Integer.valueOf(currentOldSize).equals(cachedUpTo)) {
            log.debug("[MessageCompressor] using cached summary (oldSize={})", currentOldSize);
            return summary;
        }

        String historyText = formatHistory(oldMessages);
        String promptPath = properties.getPlanning().getContextWindow().getCompression().getSummaryPrompt();
        String prompt = promptLoader.load(promptPath, Map.of("history", historyText));

        try {
            String summary = chatClient.prompt().user(prompt).call().content();
            if (summary == null) summary = "";
            session.getAttributes().put(ATTR_SUMMARY, summary);
            session.getAttributes().put(ATTR_SUMMARY_UP_TO, currentOldSize);
            log.info("[MessageCompressor] compressed {} messages into {} chars summary (session={})",
                    currentOldSize, summary.length(), session.getId());
            eventPublisher.publishEvent(
                    new ContextCompressedEvent(this, session.getId(), currentOldSize, summary.length()));
            return summary;
        } catch (Exception e) {
            log.warn("[MessageCompressor] compression failed: {}", e.getMessage());
            return "";
        }
    }

    private String formatHistory(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append("[").append(m.getRole().name()).append("] ")
              .append(m.getContent()).append("\n");
        }
        return sb.toString();
    }
}
