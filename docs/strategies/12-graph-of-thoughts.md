# Graph of Thoughts (GoT) Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `got`

---

## Summary

Graph of Thoughts extends tree-based reasoning by allowing thoughts to **merge**: multiple reasoning paths can be aggregated into a single combined thought that synthesizes their insights. This makes GoT particularly powerful for tasks requiring synthesis, ranking, or consolidation from multiple angles — problems where convergence is as important as exploration.

---

## How It Works

1. **Initialization** — the goal becomes the root node of a directed graph.
2. **Expansion** — from each active node, the LLM generates `num-thoughts` alternative next thoughts.
3. **Evaluation** — each thought node is scored.
4. **Aggregation (merge)** — if multiple high-scoring thoughts are closely related, the LLM is asked to merge them into a single synthesized node. This is the key difference from ToT — thoughts can converge, not just branch.
5. **Iteration** — steps 2–4 repeat up to `max-iterations`.
6. **Final answer** — the highest-scored leaf (or merged node) produces the final answer.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: got
    got:
      num-thoughts: 3       # thoughts generated per active node
      max-iterations: 5     # maximum graph expansion rounds
    max-steps: 50
```

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| Thought generation prompt | `prompts/got-generate.md` | Produces `num-thoughts` alternatives per node |
| Evaluation prompt | `prompts/got-evaluate.md` | Scores each thought |
| Aggregation prompt | `prompts/got-aggregate.md` | Merges related thoughts into one |

---

## When to Use

- **Summarization** — exploring different aspects then merging into one coherent summary.
- **Ranking / comparison** — generating evaluation criteria from multiple perspectives then consolidating.
- **Report generation** — multiple research threads that must eventually converge.
- Any task where diverse exploration followed by synthesis produces better outcomes than any single path.

---

## Difference from ToT

| | `tot` | `got` |
|---|---|---|
| Graph structure | Tree (branches only) | Directed graph (branches + merges) |
| Convergence | Not supported | Yes — thoughts can merge |
| Best for | Exploration, creativity | Synthesis, summarization, ranking |
| LLM calls | `O(num-thoughts × beam-width × depth)` | `O(num-thoughts × max-iterations)` + merge calls |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: got
    got:
      num-thoughts: 3
      max-iterations: 4
```

Goal: *"Compare PostgreSQL and MongoDB for a social media application."*

Iteration 1 — 3 thoughts generated:
- *"PostgreSQL strengths: ACID, relations, complex queries"*
- *"MongoDB strengths: schema flexibility, horizontal scale"*
- *"Social media requirements: high write volume, user graphs, flexible post schema"*

Iteration 2 — aggregation merges all three: *"For social media, MongoDB's flexibility suits posts/media; PostgreSQL suits user relations and analytics."*

Final answer: consolidated comparison with recommendation.
