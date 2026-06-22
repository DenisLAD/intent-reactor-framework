# Least-to-Most Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `least-to-most`

---

## Summary

Least-to-Most prompting decomposes a problem into an ordered sequence of sub-problems, starting from the simplest and building up to the original question. Each sub-problem is solved in sequence, with previous answers available as context for the next. This scaffolded approach is especially effective for educational content and progressive problem-solving.

---

## How It Works

1. **Decomposition** — the LLM receives the original problem and is asked to list the sub-problems in order from least to most complex. Sub-problems are typically skill prerequisites: *you must know X before you can understand Y*.
2. **Sequential solving** — each sub-problem is solved in order. The answer to sub-problem N becomes part of the context when solving sub-problem N+1. Tools are available for each solve step.
3. **Final solution** — with all sub-problems resolved, the LLM solves the original problem using the accumulated context.
4. A `DONE` step is emitted with the final answer.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: least-to-most
    max-steps: 50
```

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| Decomposition prompt | `prompts/ltm-decompose.md` | Lists sub-problems ordered by complexity |
| Sub-problem prompt | `prompts/react-system.md` | Solves each sub-problem (ReACT-capable) |
| Final prompt | `prompts/ltm-synthesize.md` | Combines all sub-answers into final solution |

---

## When to Use

- Educational content generation: tutoring, curriculum building.
- Compositional tasks: *"Build a REST API that does X"* → start with data model, then endpoints, then auth.
- Programming problems where concepts build on each other.
- Any task where solving the original question directly overwhelms the LLM.

---

## Difference from Self-Ask

| | `self-ask` | `least-to-most` |
|---|---|---|
| Sub-problem ordering | By dependency (any order) | By complexity (simple → complex) |
| Primary use | Factual multi-hop reasoning | Skill scaffolding, educational tasks |
| Context carryover | Yes | Yes — each answer builds the next context |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: least-to-most
```

Problem: *"Implement a binary search tree with insert and search operations in Java."*

Sub-problem 1 (least): *"What is a node in a binary search tree?"*

Sub-problem 2: *"How do you insert a node into a BST?"*

Sub-problem 3: *"How do you search for a value in a BST?"*

Sub-problem 4 (most): *"Write a complete Java class with both operations."* (with sub-answers as context)
