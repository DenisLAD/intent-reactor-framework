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
| `retreval_tree` | `RetrevalTree` — the full reasoning tree (JSON-serialized) |
| `retreval_phase` | Current phase: `EXPAND` / `SYNTHESIZE` |
| `retreval_frontier` | `List<String>` — node IDs ready for the next expansion (beam) |
| `retreval_cur_node` | ID of the node whose tool call is in progress (cleared after result is collected) |
| `retreval_patterns` | JSON-serialized `List<RetrevalPattern>` — accumulated success/failure patterns |
| `retreval_backtrack` | `[BACKTRACK: type] description` string injected into the next EXPAND prompt |

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
        validation-threshold: 0.6 # minimum score (avg self+critic) to mark a node VALIDATED
        beam-width: 2             # max frontier size (parallel candidate paths kept alive)
        final-threshold: 0.75     # score threshold to mark a node DONE immediately
        use-external-critic: true # score each candidate with a second critic prompt
        memory-enabled: true      # accumulate success/failure patterns across expansions
        max-memories: 10          # max patterns stored in retreval_patterns
    max-steps: 40
```

When `use-external-critic: false`, only self-evaluation is used. Increase `max-steps` for deep trees: each node requires at least 2 LLM calls (expand + score).

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
