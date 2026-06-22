# Reflection Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `reflection`

---

## Summary

Reflection runs the normal ReACT loop to produce an initial answer, then performs a single self-critique pass — asking the LLM to review and improve its own output — before returning the final result. Unlike [Reflexion](02-reflexion.md), it does not retry the full task; it only refines the final answer.

---

## How It Works

1. **Initial execution** — a full ReACT loop runs, producing a `DONE` response.
2. **Self-review** — the planner sends the initial answer to the LLM with a reflection prompt: *"Review your answer. What could be improved?"*
3. **Refinement** — the LLM produces a revised version of the answer.
4. The refined answer is returned as the final response.

Reflection is a post-processing step and does not change the tool execution phase.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: reflection
    max-steps: 50   # applies to the initial ReACT loop
```

No reflection-specific properties.

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| System prompt | `prompts/react-system.md` | Initial ReACT loop |
| Reflection prompt | `prompts/reflection-critique.md` | Self-review instruction |

---

## When to Use

- Whenever you want a quality gate on the final answer without retrying the full task.
- Writing tasks: improving prose, expanding incomplete answers.
- Short sessions where the overhead of one extra LLM call is acceptable.
- Complement to any domain: the reflection step is general-purpose.

---

## Difference from Reflexion

| | `reflexion` | `reflection` |
|---|---|---|
| Retry full task? | Yes (`max-reflections` times) | No |
| LLM calls | `O(retries × steps)` | `steps + 1` |
| Effect of critique | Informs the *next attempt* | Refines the *current answer* |
| Use when | First attempt often fails | First attempt is usually good enough |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: reflection
```

```java
String result = intentReactorService.process("session-1",
    "Write a brief summary of the ReACT paper.");
// 1. ReACT loop produces initial summary
// 2. Reflection LLM call: "The summary is accurate but could be more concise..."
// 3. Returns a refined, more polished summary
```
