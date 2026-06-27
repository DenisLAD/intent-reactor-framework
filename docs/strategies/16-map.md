# MAP Strategy (Modular Agentic Planner)

**Module:** `intent-reactor-strategies`
**Strategy value:** `map`

---

## Summary

MAP (Modular Agentic Planner) orchestrates five specialised LLM cognitive modules that each handle a distinct aspect of the planning process. Rather than a single monolithic planner, MAP assigns decomposition, state prediction, progress evaluation, conflict detection, and action coordination to separate prompts invoked at different points in the execution lifecycle.

---

## How It Works

**Phase 1 — PLAN** *(one call, before any tool execution)*
Four LLM modules run in sequence to produce a high-confidence subgoal plan:

1. **TaskDecomposer** — decomposes the overall goal into up to `max-subtasks` ordered subtasks. Each subtask is a concrete, tool-addressable step with optional dependency references.
2. **StatePredictor** *(if `use-state-predictor: true`)* — for each subtask, predicts the expected intermediate state and injects predictions as SYSTEM messages.
3. **Evaluator** — scores the proposed plan from 0.0 to 1.0. If the score meets `confidence-threshold` and there are no conflicts, the plan is accepted.
4. **ConflictMonitor** *(if `use-conflict-monitor: true`)* — checks whether the subtasks have resource or ordering conflicts. If a conflict is found, its description is injected back into the next TaskDecomposer call.

Steps 2–4 repeat up to `max-planning-iterations` times. Once accepted, the final plan is injected as a pinned user message into the session.

**Phase 2 — EXECUTE**
All subsequent `plan()` calls are fully delegated to the underlying `DefaultReACTPlanner`, which executes the subgoal list step by step. MAP itself does no further coordination during execution.

---

## Session state

| Attribute | Description |
|---|---|
| `map_phase` | Current phase: `PLAN` / `EXECUTE` |

All module state (subgoal list, evaluation scores, conflict context) is local to a single `plan()` call. Once the PLAN phase completes, the final subgoal plan is injected as a pinned user message and the session moves to EXECUTE, delegating all subsequent calls to the underlying ReACT planner.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: map
    strategies:
      map:
        max-subtasks: 5              # maximum subtasks from TaskDecomposer
        max-planning-iterations: 3   # refinement iterations (decompose → evaluate → monitor)
        confidence-threshold: 0.7    # Evaluator score required to accept the plan
        use-conflict-monitor: true   # run ConflictMonitor after each evaluation
        use-state-predictor: false   # run StatePredictor per subgoal (extra LLM call)
    max-steps: 50
```

---

## When to Use

- Tasks requiring structured progress tracking rather than open-ended iteration.
- Workflows where tool failures often stem from state contradictions or missing preconditions.
- Multi-step processes with a clear subtask decomposition.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Structured decomposition reduces drift | More LLM calls than plain ReACT |
| Evaluator avoids over-execution | ConflictMonitor/StatePredictor add latency |
| ConflictMonitor recovers from contradictions | Decomposition quality depends on LLM capability |
| Modular — individual modules can be toggled | Harder to reason about than linear planners |
