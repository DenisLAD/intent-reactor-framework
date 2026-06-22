# Zero-Shot CoT Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `zero-shot-cot`

---

## Summary

Zero-Shot Chain-of-Thought appends a single trigger phrase — *"Let's think step by step."* — to the user's message, eliciting step-by-step reasoning without any few-shot examples in the prompt. It is the simplest reasoning-enhancement technique in the library.

---

## How It Works

1. The planner appends the configured trigger phrase to the user's input.
2. A single LLM call is made.
3. The LLM produces reasoning followed by a final answer.
4. A `DONE` step is emitted and the response is returned.

Like [CoT](04-cot.md), Zero-Shot CoT is a decorator over ReACT and therefore supports tool use.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: zero-shot-cot
```

No strategy-specific properties.

---

## Prompts

The trigger phrase is defined in:

| Prompt | Classpath path |
|---|---|
| Trigger phrase | `prompts/zero-shot-cot-trigger.md` |

Default content: `"Let's think step by step."`

---

## When to Use

- Quick analytical questions requiring structured reasoning but no few-shot examples.
- Prototyping: cheapest way to get step-by-step output.
- When you want CoT-style reasoning without the overhead of a curated prompt.

---

## Difference from CoT

| | `cot` | `zero-shot-cot` |
|---|---|---|
| Prompt injection | Multi-line instruction block | Single trigger phrase |
| Few-shot examples | Optional (configurable) | Never |
| Output verbosity | Typically higher | Typically more concise |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: zero-shot-cot
```

```java
String result = intentReactorService.process("session-1",
    "What is 15% of 240?");
// Appends "Let's think step by step."
// LLM: "15% of 240 = 0.15 × 240 = 36."
// → returns "36"
```
