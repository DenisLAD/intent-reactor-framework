# MAP Strategy (Modular Agentic Planner)

**Module:** `intent-reactor-strategies`
**Strategy value:** `map`

---

## Summary

MAP (Modular Agentic Planner) orchestrates five specialised LLM cognitive modules that each handle a distinct aspect of the planning process. Rather than a single monolithic planner, MAP assigns decomposition, state prediction, progress evaluation, conflict detection, and action coordination to separate prompts invoked at different points in the execution lifecycle.

---

## How It Works

**Module 1 — TaskDecomposer** *(once, on first call)*
Decomposes the overall goal into an ordered list of atomic subtasks. Each subtask is a concrete, tool-addressable step.

**Module 2 — TaskCoordinator** *(every step)*
Given the current subtask, available tools, and recent observations, decides the next action: which tool to call (or a REASON step), and what parameters to use.

**Module 3 — Evaluator** *(every `eval-interval-steps` steps)*
Scores overall progress from 0.0 to 1.0. When the score exceeds `progress-threshold`, the planner moves to synthesis. This prevents unnecessary extra steps after the goal is effectively reached.

**Module 4 — ConflictMonitor** *(on tool errors, if `use-conflict-monitor: true`)*
When a tool call fails, the ConflictMonitor identifies whether the failure is a contradiction (e.g., conflicting state assumptions), a missing precondition, or a transient error. The diagnosis is injected into the next TaskCoordinator call.

**Module 5 — StatePredictor** *(every step, if `use-state-predictor: true`)*
Before executing an action, predicts the expected outcome. This prediction is compared with the actual observation and the delta is fed back to the TaskCoordinator. Disabled by default due to added LLM call overhead.

---

## Session state

| Attribute | Description |
|---|---|
| `map_phase` | Current phase: `DECOMPOSE` / `COORDINATE` / `SYNTHESIZE` |
| `map_subtasks` | `List<String>` — decomposed subtask list |
| `map_subtask_index` | Index of the current active subtask |
| `map_eval_score` | Latest Evaluator score (0.0–1.0) |
| `map_eval_step_count` | Steps since last Evaluator run |
| `map_conflict_context` | ConflictMonitor diagnosis from the last error |

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: map
    strategies:
      map:
        max-subtasks: 5           # maximum subtasks from TaskDecomposer
        progress-threshold: 0.8   # Evaluator score to consider goal done
        use-conflict-monitor: true # activate ConflictMonitor on errors
        use-state-predictor: false # activate StatePredictor (extra LLM call/step)
        eval-interval-steps: 3    # run Evaluator every N steps
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
