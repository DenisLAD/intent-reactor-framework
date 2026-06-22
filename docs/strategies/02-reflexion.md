# Reflexion Strategy

**Module:** `intent-reactor-core` (always available)
**Strategy value:** `reflexion`

---

## Summary

Reflexion adds a self-critique phase to ReACT. After the agent produces a result, an evaluator LLM call scores the outcome and generates a verbal reflection. That reflection is injected into the next planning attempt, enabling the agent to learn from its own mistakes within a single session.

---

## How It Works

1. **Initial attempt** — a full ReACT loop runs as normal, producing a candidate answer or entering a FAIL state.
2. **Evaluation** — a separate LLM call evaluates the outcome against the original goal. It returns a score (0.0–1.0) and a natural-language critique.
3. **Reflection** — the critique is appended to the session history as a SYSTEM message with the `[REFLECTION]` prefix.
4. **Retry** — the ReACT loop restarts from step 1, now with the reflection in context. The LLM can see what went wrong and adjust its approach.
5. Steps 2–4 repeat up to `max-reflections` times. If the evaluator score meets the acceptance threshold, or after all retries are exhausted, the last `DONE` output is returned as the final response.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: reflexion
    reflexion:
      max-reflections: 3    # number of reflect-and-retry cycles
```

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| System prompt | `prompts/react-system.md` | Shared with ReACT planner |
| Evaluation prompt | `prompts/reflexion-evaluate.md` | Scores the previous attempt |
| Reflection prompt | `prompts/reflexion-reflect.md` | Generates the verbal critique |

---

## When to Use

- Tasks where the first attempt frequently yields incomplete or incorrect answers.
- Coding, debugging, or planning tasks where a wrong answer is easy to detect but hard to avoid on first try.
- Sessions where accuracy matters more than latency (each reflection costs one LLM call).

---

## Tradeoffs

| Pro | Con |
|---|---|
| Higher accuracy on hard tasks | 2–3× more LLM calls |
| Self-correcting without human input | Reflection quality depends on evaluator prompt |
| Builds on ReACT (supports all tools) | Reflections accumulate in context window |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: reflexion
    reflexion:
      max-reflections: 2
    max-steps: 40
```

With `max-reflections: 2`, the agent may make up to 3 total attempts (1 initial + 2 retries), each guided by the previous reflection.
