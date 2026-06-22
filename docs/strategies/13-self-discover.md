# Self-Discover Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `self-discover`

---

## Summary

Self-Discover is a meta-cognitive strategy: before attempting the task, the LLM selects and composes the reasoning modules most appropriate for the problem at hand. It builds a custom reasoning structure tailored to the specific task, then follows that structure to produce an answer. This allows the same framework to adapt its problem-solving approach to novel tasks.

---

## How It Works

Self-Discover operates in three explicit phases before any tool execution:

**Phase 1 — Select**
The LLM is given the goal and a catalogue of atomic reasoning modules (e.g., *"Break into sub-problems"*, *"Consider edge cases"*, *"Think about constraints"*, *"Use analogical reasoning"*). It selects the subset most relevant to the current task.

**Phase 2 — Adapt**
The selected modules are refined into task-specific instructions. Generic module descriptions become concrete directives for this problem.

**Phase 3 — Implement (structured reasoning)**
The adapted reasoning plan is executed step by step. Each step follows the adapted module. After the reasoning structure completes, the LLM synthesizes a final answer.

The full plan is then handed to the ReACT loop, which may invoke tools during the implementation phase.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: self-discover
    max-steps: 50
```

No strategy-specific properties. The reasoning module catalogue is defined in the prompt template.

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| Select prompt | `prompts/self-discover-select.md` | LLM chooses relevant reasoning modules |
| Adapt prompt | `prompts/self-discover-adapt.md` | Specializes modules for the current task |
| Implement prompt | `prompts/self-discover-implement.md` | Executes the composed reasoning plan |

---

## When to Use

- Novel tasks where no single established strategy is obviously the best fit.
- Diverse workloads: a single deployed agent handles many different task types.
- Complex problems with multiple interacting constraints.
- Exploratory research tasks.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Adapts its own strategy to the task | 3 LLM calls before execution begins |
| No hardcoded problem-solving template | Quality depends on module catalogue quality |
| Strong on novel / unusual tasks | Overkill for routine tasks |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: self-discover
```

Goal: *"Design a fair algorithm to allocate limited COVID vaccines across cities with different populations and risk levels."*

Phase 1 selected modules: *"Break into sub-problems"*, *"Consider multiple stakeholders"*, *"Identify constraints"*, *"Reason about fairness criteria"*.

Phase 2 adapted: *"Sub-problems: (1) define fairness, (2) model population risk, (3) propose allocation formula, (4) stress-test edge cases."*

Phase 3: each sub-problem solved in sequence, potentially using tools to fetch demographic data, then synthesized into a final algorithm proposal.
