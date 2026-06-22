# Chain-of-Thought (CoT) Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `cot`

---

## Summary

Chain-of-Thought prompting instructs the LLM to reason step-by-step before giving a final answer. Unlike ReACT, CoT does not use the tool execution loop — it performs a single LLM call with a reasoning-oriented prompt and returns the answer directly.

---

## How It Works

1. The CoT planner prepends a chain-of-thought instruction to the prompt: *"Think through this step by step before answering."*
2. A single LLM call is made. The model's output contains both the intermediate reasoning and the final answer.
3. The planner emits a `DONE` step with the full response as output.
4. The response is returned immediately; no `max-steps` loop runs.

Because CoT is a decorator over ReACT, you can **combine** CoT preprocessing with tool use: the CoT planner generates an initial reasoning plan, then hands off to the ReACT loop for tool execution guided by that plan.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: cot
```

No additional properties. `max-steps` has no effect for pure CoT (no iteration loop).

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| CoT instruction | `prompts/cot-instruction.md` | Injected before the user message |

---

## When to Use

- Math problems, logic puzzles, and analytical tasks where showing work improves accuracy.
- Questions where the LLM has sufficient knowledge without external tools.
- When latency matters and a single-call solution is acceptable.
- As a building block: enable tools alongside CoT for hybrid "reason-then-act" pipelines.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Single LLM call — fast and cheap | No tool access by default |
| Higher accuracy than zero-shot on complex reasoning | Relies entirely on the LLM's parametric knowledge |
| Simple to enable | May produce verbose reasoning in output |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: cot
```

```java
String result = intentReactorService.process("session-1",
    "If a train travels 120 km in 1.5 hours, what is its average speed?");
// The LLM reasons: "Speed = distance / time = 120 / 1.5 = 80 km/h"
// → returns "The average speed is 80 km/h."
```
