package com.intentreactor.api.event;

/**
 * Published when the context compression fires and replaces old session messages
 * with an LLM-generated summary. Only emitted when compression is enabled
 * ({@code intent-reactor.planning.context-window.compression.enabled=true})
 * and the estimated token count exceeds the configured trigger threshold.
 */
public class ContextCompressedEvent extends IntentReactorEvent {

    private final int compressedMessageCount;
    private final int summaryLength;

    public ContextCompressedEvent(Object source, String sessionId,
                                  int compressedMessageCount, int summaryLength) {
        super(source, sessionId);
        this.compressedMessageCount = compressedMessageCount;
        this.summaryLength = summaryLength;
    }

    /** Number of old messages that were replaced by the summary. */
    public int getCompressedMessageCount() {
        return compressedMessageCount;
    }

    /** Character length of the generated summary. */
    public int getSummaryLength() {
        return summaryLength;
    }
}
