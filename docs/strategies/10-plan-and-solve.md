# Plan-and-Solve Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `plan-and-solve`

---

## Summary

Plan-and-Solve separates planning from execution into two explicit phases. First, the LLM produces a complete numbered plan for the task. Then the plan is executed step by step, with each step potentially using tools. This reduces arithmetic and logical errors by forcing the LLM to think ahead before acting.

---

## How It Works

1. **Planning phase** — the LLM receives the goal and is asked to devise a complete, numbered execution plan: *"First, devise a plan. Then carry it out."* The plan is a list of concrete actions.
2. **Execution phase** — each step of the plan is handed to the ReACT loop in order. The ReACT loop may invoke tools for each step. The result of each step is recorded.
3. After all steps complete (or if a step emits `FAIL`), a final synthesis LLM call assembles the step results into a coherent response.
4. A `DONE` step is emitted.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: plan-and-solve
    max-steps: 50   # applies to the execution phase
```

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| Planning prompt | `prompts/ps-plan.md` | Generates the numbered execution plan |
| Step execution prompt | `prompts/react-system.md` | ReACT loop for each step |
| Synthesis prompt | `prompts/ps-synthesize.md` | Combines step outputs into final answer |

---

## When to Use

- Multi-step tasks with a clear beginning and end: *"Analyze this codebase, identify issues, and generate a refactoring report."*
- Tasks where a written plan helps catch missing steps before execution.
- When you want the planning and execution stages to be clearly separated for auditability.
- Structured automation workflows.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Explicit plan is inspectable and loggable | Rigidity — plan may not adapt well to unexpected tool results |
| Reduces mid-execution confusion | Extra planning LLM call |
| Lower error rate on multi-step arithmetic/logic | Over-planning on simple tasks |

---

## Difference from ReACT

| | `react` | `plan-and-solve` |
|---|---|---|
| Plan creation | Implicit, one step at a time | Explicit full plan upfront |
| Adaptability | High — can pivot each step | Lower — follows the initial plan |
| Auditability | Via step history | Via explicit plan document |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: plan-and-solve
```

Goal: *"Summarize all .log files in /var/logs and count total error lines."*

Plan generated:
1. List all .log files in /var/logs.
2. Read each file.
3. Count lines containing "ERROR".
4. Summarize: total files, total errors, most frequent error.

Execution: ReACT loop calls `glob`, `read_file`, `calculator` as planned, then synthesizes the summary.
