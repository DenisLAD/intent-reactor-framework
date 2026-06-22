# Context Window Management

Long conversations accumulate hundreds of messages, eventually exceeding the LLM's context limit. IntentReactor provides several mechanisms to keep the message history within bounds.

---

## Sliding window

The most basic control: keep only the most recent N messages.

```yaml
intent-reactor:
  planning:
    context-window:
      max-messages: 20   # default; 0 = unlimited
```

When the history exceeds `max-messages`, the oldest messages are dropped. **Pinned messages** are exempt — they are re-inserted at their original chronological position even if they fall outside the window. This ensures the original user goal and key context are never lost.

---

## Per-message character limits

Truncate individual messages that are too long:

```yaml
intent-reactor:
  planning:
    context-window:
      max-message-chars: 8000     # default; 0 = unlimited
      max-snapshot-chars: 30000   # default; applied to take_snapshot tool results
      truncation-suffix: "... [truncated]"  # appended when truncated
```

`max-snapshot-chars` applies specifically to messages whose content starts with `[TOOL_RESULT] take_snapshot:`. DOM snapshots tend to be very large; this allows a higher limit for them while keeping regular messages shorter.

---

## Snapshot deduplication

The `take_snapshot` tool (used in browser-automation scenarios) may be called many times, producing near-duplicate large SYSTEM messages. The framework automatically removes stale snapshots, keeping only the **most recent** one.

This happens in `DefaultReACTPlanner.buildMessages()` before constructing the prompt, so session storage is unaffected — only the messages sent to the LLM are deduplicated.

---

## LLM-based context compression

When the sliding window alone is not sufficient, enable token-aware compression. Old messages that have scrolled out of the window are summarized by the LLM and injected back as a single `[ИСТОРИЯ ДИАЛОГА]` (dialog history) prefix message.

```yaml
intent-reactor:
  planning:
    context-window:
      compression:
        enabled: true
        max-tokens: 4000        # estimated token budget
        chars-per-token: 4      # characters per token estimate
        trigger-ratio: 0.85     # compress when estimated tokens > max-tokens * ratio
        summary-prompt: classpath:prompts/context-compression-ru.md
```

**How it works:**

1. After building the message list, the planner estimates the total token count as `totalChars / charsPerToken`.
2. If `estimatedTokens > maxTokens × triggerRatio`, the messages **outside** the sliding window (excluding pinned messages) are passed to the LLM with the compression prompt.
3. The LLM returns a concise summary.
4. The summary is inserted at position 1 (immediately after the system prompt) as a `UserMessage`.
5. A `ContextCompressedEvent` is published.

> **Note:** Compression is disabled by default (`enabled: false`) because it incurs an additional LLM call. Enable it only for sessions expected to run for many turns.

---

## Message pinning

Pin a message to protect it from eviction and compression:

```java
// Pin the next user message
session.getAttributes().put(SessionAttributeKeys.PIN_NEXT_USER_MESSAGE, true);
reactor.process(sessionId, "Critical instruction: always respond in English.");
```

The framework also automatically pins:
- The **first** message of every session (original user goal).
- Messages sent immediately after a confirmation resume (correction context).

---

## Complete configuration reference

```yaml
intent-reactor:
  planning:
    context-window:
      max-messages: 20              # sliding window size (0 = unlimited)
      max-message-chars: 8000       # per-message char limit (0 = unlimited)
      max-snapshot-chars: 30000     # limit for take_snapshot messages
      truncation-suffix: "... [truncated]"
      compression:
        enabled: false
        max-tokens: 4000
        chars-per-token: 4
        trigger-ratio: 0.85
        summary-prompt: classpath:prompts/context-compression-ru.md
```

---

## ContextCompressedEvent

Published when compression runs:

```java
@EventListener
public void onCompression(ContextCompressedEvent event) {
    log.info("Session {} — compressed {} messages into {} chars",
        event.getSessionId(),
        event.getCompressedMessageCount(),
        event.getSummaryLength());
}
```
