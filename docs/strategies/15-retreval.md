# ReTreVal Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `retreval`

---

## Summary

ReTreVal (Reasoning Tree with Validation) is a training-free strategy that builds a tree of reasoning steps, applies dual validation to each candidate node, and backtracks on failure with typed error-context injection. Successful reasoning patterns are accumulated in session-scoped memory and reused in subsequent steps to improve consistency.

---

## How It Works

**Phase 1 — EXPAND**
The LLM generates `candidates-per-step` candidate next steps given the current goal, available tools, and accumulated memory patterns. Each candidate carries a proposed action (tool call or reasoning step) and a brief justification.

**Phase 2 — VALIDATE**
Each candidate is scored twice:
1. **Self-eval** — the candidate evaluates itself (0.0–1.0). If the score exceeds the short-circuit threshold (0.8), critic scoring is skipped.
2. **Critic scoring** — a separate critic prompt scores feasibility, relevance, and risk.

The candidate with the highest combined score is selected. Candidates scoring below `validation-threshold` are rejected.

**Phase 3 — EXECUTE**
The selected candidate node is executed (tool call or REASON step). The observation is absorbed back into the tree node.

**Phase 4 — BACKTRACK** (on failure)
If execution fails or all candidates are rejected, the planner backtracks to the parent node. The failure type and error message are injected into the next EXPAND prompt so the LLM avoids the same mistake.

**Phase 5 — SYNTHESIZE**
Once the goal is reached or the tree depth limit is hit, all executed nodes are summarised into a final answer.

---

## Session state

| Attribute | Description |
|---|---|
| `retreval_tree` | `RetrevalTree` — the full reasoning tree |
| `retreval_memory` | `List<RetrevalPattern>` — successful step patterns accumulated across turns |
| `retreval_phase` | Current phase: `INITIAL` / `EXPAND` / `VALIDATE` / `EXECUTE` / `BACKTRACK` / `SYNTHESIZE` |
| `retreval_candidates` | Candidate nodes being validated in the current VALIDATE phase |
| `retreval_validate_idx` | Index of the candidate currently being scored |

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: retreval
    strategies:
      retreval:
        max-tree-depth: 4         # tree depth limit before forced SYNTHESIZE
        candidates-per-step: 3    # candidates generated per EXPAND
        validation-threshold: 0.6 # minimum score to accept a candidate
    max-steps: 40
```

The short-circuit threshold (0.8) is fixed in code. Increase `max-steps` for deep trees: each node requires at least 2 LLM calls (expand + validate).

---

## When to Use

- Complex multi-step tasks where naive ReACT gets stuck in local optima.
- Tasks that benefit from exploring multiple candidate next steps before committing.
- Workflows where past failures should inform future step generation.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Systematic exploration with validation | Higher LLM call count than plain ReACT |
| Typed-failure backtracking avoids repeated mistakes | Memory growth across long sessions |
| Accumulated patterns improve consistency | `max-steps` must be tuned for deep trees |
| Training-free — works with any LLM | More complex to debug than linear strategies |
