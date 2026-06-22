# Multi-Intent Processing

When `IntentPreprocessor` detects **more than one intent** in a single user message, the framework automatically dispatches a multi-intent execution strategy instead of the normal single-intent loop.

---

## When it triggers

Multi-intent fires when `IntentAnalysisResult.getIntents().size() > 1`. If the result is uncertain or contains zero intents, the framework falls through to single-intent processing regardless of this setting.

Example message that might produce two intents:
> *"Check the weather in Berlin and also look up order ORD-456."*

The preprocessor may return:
```json
{
  "intents": [
    { "name": "weather_lookup", "confidence": 0.95, "attributes": { "city": "Berlin" } },
    { "name": "order_lookup",   "confidence": 0.92, "attributes": { "orderId": "ORD-456" } }
  ]
}
```

---

## Strategies

Select via `intent-reactor.planning.multi-intent.strategy`:

| Value | Description |
|---|---|
| `sequential` (default) | Execute intents one after another in detected order |
| `parallel` | Execute all intents concurrently in isolated sessions |
| `llm-driven` | Ask the LLM to order intents before sequential execution |

---

## sequential

```yaml
intent-reactor:
  planning:
    multi-intent:
      strategy: sequential
```

Intents are processed in the order returned by `IntentPreprocessor`. Each intent gets its own complete planning cycle (including tool calls). If one intent requires **confirmation**, execution pauses; after `proceedAfterConfirmation()` the remaining intents continue automatically.

**Result merging** — the final text concatenates results in the format:
```
[weather_lookup] It's 22°C and sunny in Berlin.; [order_lookup] Order ORD-456 is shipped, ETA 2025-08-01.
```

If any intent fails, the merged response has `status=FAILED` with all partial results included.

---

## parallel

```yaml
intent-reactor:
  planning:
    multi-intent:
      strategy: parallel
    parallel-timeout: PT60S   # per-intent timeout (default)
```

Each intent runs in a **cloned session** (independent `SessionState` with a derived ID like `originalId-parallel-weatherlookup`). All clones run concurrently via `CompletableFuture`.

Behaviors:
- If an intent times out (`parallel-timeout`), its future is cancelled and a `FAILED` result is recorded for that intent.
- Risky-tool confirmation is **not supported** in parallel mode — confirmation requires a pause that cannot be safely serialized across concurrent futures. Mark parallel tools as non-risky or use sequential mode for confirmation flows.
- Results are merged in the order intents were originally detected.

---

## llm-driven

```yaml
intent-reactor:
  planning:
    multi-intent:
      strategy: llm-driven
```

Before executing, the framework makes one additional LLM call with all detected intents and asks the model to produce an optimal execution order (e.g., "resolve the order first to get the city before looking up weather"). The reordered list is then executed sequentially.

If the LLM ordering call fails, the framework falls back to sorting intents by confidence score (highest first).

---

## MultiIntentContext

The orchestration state is stored in `session.attributes["multiIntentState"]` as a `MultiIntentContext`:

```
MultiIntentContext {
    List<Intent> pendingIntents      // intents not yet processed
    List<Intent> completedIntents    // intents already finished
    Intent currentIntent             // the one currently executing
    String strategy                  // "sequential" | "parallel" | "llm-driven"
    Map<String, ReactorResponse> results  // intentName → response (insertion-ordered)
}
```

This object is persisted across planning cycles and across confirmation pauses, so the framework always knows where it left off.

---

## Example: handling the merged response

```java
ReactorResponse response = reactor.process("session-1",
    "Check Berlin weather and look up order ORD-456");

if (response.getStatus() == PlanStatus.COMPLETED) {
    // finalText contains both results concatenated
    System.out.println(response.getFinalText());

    // individual tool calls available in actions list
    response.getActions().forEach(action ->
        System.out.printf("Tool: %s → %s%n", action.getToolName(), action.getResult()));
}
```
