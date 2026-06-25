# HTP Strategy (HyperTree Planning)

**Module:** `intent-reactor-strategies`
**Strategy value:** `htp`

---

## Summary

HTP (HyperTree Planning) decomposes the overall goal into an ordered list of constrained subgoals, creates a mini-plan for each subgoal, executes the plan steps sequentially, and optionally verifies whether each subgoal was actually achieved — re-planning if it was not. Unlike Tree-of-Thoughts, HTP nodes are goal-oriented subgoals rather than reasoning alternatives, and the search is depth-first with a fixed execution order rather than exploratory.

---

## How It Works

**Phase 1 — DECOMPOSE**
The LLM breaks the overall goal into up to `max-subgoals` ordered subgoals. Each subgoal includes a description and a list of constraints (budget, time limits, required preconditions, etc.). The result is stored as a `HyperTree`.

**Phase 2 — PLAN_NODE**
For the current pending subgoal, the LLM produces a concrete step-by-step plan (up to `max-steps-per-node` steps). Each step may reference a tool by name or be a reasoning step (no tool).

**Phase 3 — EXECUTE**
Steps are executed in order. Tool results are absorbed as observations into the corresponding step record. When all steps complete, the planner transitions to REFINE (if enabled) or ADVANCE.

**Phase 4 — REFINE** *(if `refinement-enabled: true`)*
A critic prompt evaluates whether the subgoal was achieved given the step observations. If not, the planner re-enters PLAN_NODE for the same subgoal with the failure reason injected as context. After `max-refinement-retries` failed attempts, the subgoal is marked `FAILED` and execution continues.

**Phase 5 — ADVANCE**
Moves to the next pending subgoal. If no subgoals remain, transitions to SYNTHESIZE.

**Phase 6 — SYNTHESIZE**
Collects the status and observations of all subgoals and synthesizes a final answer.

---

## Session state

| Attribute | Description |
|---|---|
| `htp_tree` | `HyperTree` — full subgoal tree with step records and statuses |
| `htp_phase` | Current phase: `DECOMPOSE` / `PLAN_NODE` / `EXECUTE` / `REFINE` / `ADVANCE` / `SYNTHESIZE` |

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: htp
    strategies:
      htp:
        max-subgoals: 4            # maximum subgoals produced by DECOMPOSE
        max-steps-per-node: 5     # maximum steps planned per subgoal
        refinement-enabled: true  # run REFINE after each subgoal
        max-refinement-retries: 2 # retries before marking subgoal FAILED
    max-steps: 60
```

Budget: approximately `max-subgoals × (1 plan + max-steps-per-node execute + 1 refine)` LLM calls. Increase `max-steps` accordingly.

---

## When to Use

- Goals that decompose cleanly into ordered, independently verifiable subgoals.
- Tasks with explicit constraints per subgoal (resource limits, ordering requirements).
- Workflows where verifying completion of each subgoal before proceeding is important.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Explicit hierarchical structure | Decompose quality determines everything |
| Per-subgoal refinement improves reliability | High LLM call count with refinement enabled |
| Constraints guide planning within each node | Sequential execution; no parallelism |
| Failed subgoals are isolated and marked | Subgoal plan quality depends on LLM capability |
