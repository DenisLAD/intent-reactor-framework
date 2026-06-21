package com.intentreactor.core.planner;

import com.intentreactor.api.Message;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.event.ContextCompressedEvent;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

/**
 * Compresses old session messages into a concise LLM-generated summary.
 * Used by {@link DefaultReACTPlanner#buildMessages} when estimated token count
 * approaches the configured limit. The summary is cached in session.attributes
 * so that repeated plan() calls within the same session avoid redundant LLM calls.
 *
 * <p>Token counting uses character-based approximation (chars / charsPerToken) to stay
 * proactive — real token counts from the API arrive only after the request is sent.
 *
 * <p>When compression fires (not from cache), a {@link ContextCompressedEvent} is published
 * so that SSE bridges and audit listeners can surface the event to the user.
 */
public class MessageCompressor {

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
     * Estimates the total token count of the given Spring AI messages using character approximation.
     */
    public int estimateTokens(List<org.springframework.ai.chat.messages.Message> messages) {
        int charsPerToken = properties.getPlanning().getContextWindow().getCompression().getCharsPerToken();
        int totalChars = messages.stream()
                .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                .sum();
        return totalChars / Math.max(1, charsPerToken);
    }

    /**
     * Compresses {@code oldMessages} into a single summary string via LLM.
     *
     * <p>Result is cached in {@code session.attributes[_contextSummary]} keyed by
     * {@code _contextSummaryUpTo = oldMessages.size()}. The cache is reused as long as
     * the old-messages group doesn't grow (i.e., the sliding window hasn't advanced further).
     *
     * <p>On a fresh compression (not from cache), publishes a {@link ContextCompressedEvent}.
     *
     * @param oldMessages API-layer messages that have been pushed out of the context window
     * @param session     current session (used for caching; attributes are mutated in-place)
     * @return compressed summary text; empty string on LLM failure
     */
    public String compress(List<Message> oldMessages, SessionState session) {
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
