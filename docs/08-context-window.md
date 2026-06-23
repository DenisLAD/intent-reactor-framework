# Context Window Management

Long conversations accumulate hundreds of messages, eventually exceeding the LLM's context limit. IntentReactor provides several mechanisms to keep the message history within bounds.

---

## Message processing pipeline

Before every `DefaultReACTPlanner` / `ReflexionPlanner` call, the message history passes through two ordered extension pipelines. **`LATSPlanner` does not use these pipelines.**

### MessageContextPreProcessor

Runs on the **full session message list** before the sliding window is applied. Useful for injecting warm-up messages or globally filtering message categories.

```java
@Component
public class InjectPreamblePre implements MessageContextPreProcessor {

    @Override
    public List<Message> process(List<Message> allMessages, SessionState session) {
        // inject a reminder at the top before windowing
        List<Message> result = new ArrayList<>(allMessages);
        result.add(0, Message.system("Always respond in English."));
        return result;
    }

    @Override
    public int getOrder() { return -100; }
}
```

### MessageContextPostProcessor

Runs on the **windowed message list** after the sliding window has been applied, before conversion to LLM types. Receives a `MessageBuildContext` that exposes:
- `getEvictedMessages()` — messages pushed out of the window (useful for compression)
- `setCharLimit(message, limit)` — per-message character limit override, read by the truncation step

```java
@Component
public class DeduplicateSnapshotPost implements MessageContextPostProcessor {

    @Override
    public List<Message> process(List<Message> messages, MessageBuildContext ctx) {
        // keep only the most recent take_snapshot result
        // ...
        return deduped;
    }

    @Override
    public int getOrder() { return 0; }  // run before compression
}
```

Recommended order values:

| Order | Purpose |
|---|---|
| `0` | Snapshot deduplication, content normalization |
| `200` | LLM-based compression (`MessageCompressor`) |
| `Integer.MAX_VALUE - 100` | `MessageCompressor` built-in: `Ordered.LOWEST_PRECEDENCE - 100` |

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
      truncation-suffix: "... [truncated]"  # appended when truncated
```

For messages that legitimately need a larger limit (e.g., DOM snapshots from browser automation), register a `MessageContextPostProcessor` and call `context.setCharLimit(message, largerLimit)` for the relevant messages. The truncation step reads these overrides.

---

## LLM-based context compression

When the sliding window alone is not sufficient, enable token-aware compression. Old messages that have scrolled out of the window are summarized by the LLM and injected back as a single `[ИСТОРИЯ ДИАЛОГА]` (dialog history) prefix message.

`MessageCompressor` is the built-in implementation; it is registered automatically as a `MessageContextPostProcessor` at order `Ordered.LOWEST_PRECEDENCE - 100` when compression is enabled.

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

1. After the post-processor pipeline runs, `MessageCompressor` estimates the total token count as `totalChars / charsPerToken`.
2. If `estimatedTokens > maxTokens × triggerRatio`, the evicted messages from `MessageBuildContext.getEvictedMessages()` are passed to the LLM with the compression prompt.
3. The LLM returns a concise summary.
4. The summary is inserted at position 0 of the windowed list as a `UserMessage`.
5. The result is cached in `session.attributes["_contextSummary"]`, keyed by the evicted-message count, to avoid redundant LLM calls on subsequent `plan()` iterations within the same session.
6. A `ContextCompressedEvent` is published.

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
