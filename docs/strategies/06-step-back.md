# Step-Back Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `step-back`

---

## Summary

Step-Back prompting asks the LLM to first abstract the question to a more general principle, then answer the general question, and finally use that broader understanding to answer the original specific question. This improves accuracy on domain-specific and factual questions.

---

## How It Works

1. **Abstraction** — the planner asks the LLM: *"What is the high-level principle or concept behind this question?"* A second LLM call produces a generalized version of the problem.
2. **General answer** — the LLM answers the generalized question, retrieving relevant background knowledge.
3. **Specific answer** — the original question is re-presented along with the general answer as context. The LLM produces the final response.
4. A `DONE` step is emitted with the final answer.

Step-Back is a decorator over ReACT. If tools are registered, they are available during step 3 (the specific-answer phase).

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: step-back
```

No strategy-specific properties.

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| Abstraction prompt | `prompts/step-back-abstract.md` | Elicits the generalized version |
| Grounding prompt | `prompts/step-back-ground.md` | Answers the general question |
| Final prompt | `prompts/react-system.md` | Combines general + specific context |

---

## When to Use

- Domain-specific Q&A requiring background knowledge (medical, legal, scientific).
- Questions where knowing the principle first leads to better reasoning.
- Tasks where the model tends to fixate on surface details rather than underlying concepts.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Better accuracy on knowledge-intensive tasks | 2 extra LLM calls per request |
| Produces well-grounded answers | Abstraction step may generalize incorrectly |
| Works alongside tools | Slightly higher latency |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: step-back
```

Question: *"What antibiotic is effective against MRSA?"*

Step 1 abstraction: *"What are the principles for treating antibiotic-resistant bacterial infections?"*

Step 2 general answer: *"Resistant infections require agents active against resistant strains: vancomycin, linezolid, daptomycin..."*

Step 3 specific answer: *"For MRSA specifically, vancomycin is the first-line treatment..."*
