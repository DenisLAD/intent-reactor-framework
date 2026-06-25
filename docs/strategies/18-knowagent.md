# KnowAgent Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `knowagent`

---

## Summary

KnowAgent is a decorator over the base ReACT planner that enriches planning with an Action Knowledge Base (KB). The KB captures preconditions, postconditions, and contraindications for each available tool, derived from tool descriptions and optionally enriched by an LLM call. Before each planning iteration, tools whose preconditions are not satisfied by the current session state are filtered out, reducing the risk of the planner calling tools in the wrong order or with missing context.

---

## How It Works

**Step 1 — KB initialisation** *(once per session)*
On the first call, KnowAgent builds the KB from tool metadata:

- **Pattern-based extraction** — regex patterns scan each tool's description for precondition language (`requires`, `must first`, `after calling`…), postcondition language (`sets`, `creates`, `returns`…), and contraindication language (`do not`, `never`, `avoid`…).
- **LLM enrichment** *(if `enrich-knowledge: true`)* — a single LLM prompt processes all tools simultaneously and returns a structured JSON KB with richer preconditions, postconditions, and contraindications. This adds one extra LLM call at session start.

The KB is stored in `session.attributes["knowagent_kb"]` and reused for all subsequent calls.

**Step 2 — Tool filtering** *(every call, if `filter-by-preconditions: true`)*
Before delegating to the underlying ReACT planner, KnowAgent inspects the session message history for evidence that each tool's preconditions are already satisfied (e.g., a `[TOOL_RESULT]` message from a prerequisite tool). Tools with unsatisfied preconditions are removed from the available tool list for this planning step.

**Step 3 — KB context injection**
The KB is formatted as a system message and prepended to the messages passed to the underlying ReACT planner, so the LLM is aware of tool relationships even for tools not filtered out.

**Step 4 — Delegation**
Planning is fully delegated to the underlying `DefaultReACTPlanner`. KnowAgent only wraps the tool list and message context.

---

## Session state

| Attribute | Description |
|---|---|
| `knowagent_kb` | `Map<String, ToolKnowledge>` — per-tool preconditions, postconditions, contraindications |
| `knowagent_initialized` | `Boolean` — whether the KB has been built for this session |

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: knowagent
    strategies:
      know-agent:
        enrich-knowledge: false    # true = LLM-enrich KB on first call (extra latency)
        filter-by-preconditions: true # remove tools with unsatisfied preconditions
    max-steps: 10
```

Set `enrich-knowledge: true` for tool-heavy scenarios where tools have complex relationships not fully captured in their descriptions. The LLM enrichment call is made once per session.

---

## When to Use

- Workflows with many tools that have explicit ordering requirements.
- Scenarios where the LLM repeatedly calls tools out of order or with missing context.
- Any ReACT workload where precondition-aware filtering can reduce failed tool calls.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Reduces invalid tool calls | `enrich-knowledge` adds a one-time LLM call |
| KB context improves LLM awareness of tool relationships | Pattern-based extraction may miss implicit preconditions |
| Fully compatible with all ReACT features | Filtering may remove valid tools if precondition detection is imprecise |
| Zero code change — drop-in replacement for `react` | Small overhead per call for filtering and context injection |
