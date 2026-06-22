# LATS Strategy

**Module:** `intent-reactor-core` (always available)
**Strategy value:** `lats`

---

## Summary

LATS (Language Agent Tree Search) combines Monte Carlo Tree Search with LLM reasoning. Instead of following a single trajectory, it simultaneously explores `num-candidates` alternative next steps, simulates each (using `SimulatableTool` where available), scores them, and commits to the highest-scoring path. The result is a best-first search over the space of possible action sequences.

---

## How It Works

1. **Expansion** — at each step the LLM is asked to generate `num-candidates` alternative `PlanStep` proposals. Each is a different action the agent might take.
2. **Simulation** — for each candidate step that involves a tool:
   - If the tool implements `SimulatableTool`, the `simulate()` method is called instead of the real tool. Simulation is fast and side-effect-free.
   - If no simulation is available, a lightweight LLM call predicts the likely tool output.
3. **Evaluation** — the LLM (or a dedicated evaluator call) scores each simulated trajectory from 0.0 to 1.0 based on how likely it is to achieve the goal.
4. **Selection** — the candidate with the highest score is chosen and the actual tool is executed (with confirmation flow if needed).
5. **Backpropagation** — the selected score is recorded in the tree node for future value estimates (used when `tree-depth > 1`).
6. Steps 1–5 repeat until `DONE`, `FAIL`, or `max-steps` is reached.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: lats
    lats:
      num-candidates: 3       # parallel branches explored per step
      min-value-threshold: 0.6  # minimum score to accept a candidate (0.0–1.0)
      tree-depth: 5           # depth of the search tree
    max-steps: 50
```

Setting `num-candidates: 1` degrades LATS to standard ReACT (useful for testing).

---

## SimulatableTool

Implement `SimulatableTool` on any `Tool` that can safely run in a dry-run mode:

```java
@Component
public class DatabaseTool implements Tool, SimulatableTool {

    @Override
    public ToolResult execute(ToolInput input) {
        // actual DB write
    }

    @Override
    public ToolResult simulate(ToolInput input) {
        // return a realistic mock result; do not modify state
        return ToolResult.ok("Simulated: 3 rows would be affected.");
    }

    // ... getName(), getDescription(), getSchema(), isRisky(), isGenerator()
}
```

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| System prompt | `prompts/react-system.md` | Shared with ReACT planner |
| Candidate generation prompt | `prompts/lats-expand.md` | Generates `num-candidates` alternatives |
| Evaluation prompt | `prompts/lats-evaluate.md` | Scores each simulated trajectory |

---

## When to Use

- Tasks where many paths lead to the goal and the first option is not obviously best.
- Exploration tasks: code generation, query planning, test design.
- Scenarios where wrong tool calls are costly (LATS avoids bad actions via simulation).

---

## Tradeoffs

| Pro | Con |
|---|---|
| Finds better paths than greedy ReACT | `num-candidates × LLM calls` per step |
| Avoids irreversible bad actions via simulation | Higher latency |
| Configurable exploration width and depth | Tools without `SimulatableTool` require an extra LLM call per candidate |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: lats
    lats:
      num-candidates: 2
      min-value-threshold: 0.7
      tree-depth: 3
    max-steps: 30
```

With these settings, LATS explores 2 options per step, requires at least 70% confidence to accept a branch, and searches at most 3 levels deep.
