# Tree of Thoughts (ToT) Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `tot`

---

## Summary

Tree of Thoughts explores multiple reasoning paths simultaneously using a tree structure. At each step, the LLM generates `num-thoughts` different "thought" continuations. Each thought is independently evaluated. Only the top `beam-width` thoughts are kept and expanded further. This beam-search approach finds high-quality reasoning paths that a greedy strategy would miss.

---

## How It Works

1. **Root** — the original goal becomes the root of the thought tree.
2. **Expansion** — from the current beam (set of active paths), the LLM generates `num-thoughts` alternative next thoughts for each path.
3. **Evaluation** — each thought is scored by the LLM on a 0–10 scale: *"How promising is this line of thinking for solving the goal?"*
4. **Selection (beam pruning)** — the top `beam-width` thoughts across all paths are retained.
5. **Recursion** — steps 2–4 repeat up to `max-depth` levels.
6. **Answer extraction** — when depth limit is reached or a path reaches a natural conclusion (`DONE`), the best-scored leaf produces the final answer.

Tool calls may appear as thoughts — *"I should read the file config.yaml"* — and are executed before that path continues.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: tot
    tot:
      num-thoughts: 3    # thoughts generated per path per step
      max-depth: 5       # maximum tree depth
      beam-width: 2      # paths kept after each pruning step
    max-steps: 50
```

Total LLM calls per step: approximately `num-thoughts × beam-width + beam-width` (generation + evaluation).

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| Thought generation prompt | `prompts/tot-generate.md` | Produces `num-thoughts` alternatives |
| Thought evaluation prompt | `prompts/tot-evaluate.md` | Scores each thought 0–10 |
| System prompt | `prompts/react-system.md` | Tool-use capable base |

---

## When to Use

- Creative tasks: writing, brainstorming, design.
- Open-ended reasoning where multiple approaches are plausible.
- Problems requiring backtracking (ToT naturally avoids dead ends).
- Scenario analysis: *"What are the implications of X?"*

---

## Tradeoffs

| Pro | Con |
|---|---|
| Explores diverse reasoning paths | High LLM call count: O(num-thoughts × beam-width × depth) |
| Avoids dead-end paths early | Slow for simple tasks |
| Best-first exploration | High cost per request |

---

## Difference from LATS

| | `lats` | `tot` |
|---|---|---|
| Focus | Action selection (tool calls) | Reasoning step selection (thoughts) |
| Simulation | SimulatableTool / LLM prediction | LLM scoring only |
| Primary use | Tool-heavy agentic tasks | Reasoning / creative tasks |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: tot
    tot:
      num-thoughts: 3
      max-depth: 4
      beam-width: 2
```

Goal: *"Design a caching strategy for a high-traffic API."*

Depth 1 thoughts:
- T1: *"Use Redis with TTL-based expiration"* (score: 8)
- T2: *"Implement a CDN layer"* (score: 7)
- T3: *"Use application-level LRU cache"* (score: 6)

Beam keeps T1, T2. Each expands further, exploring trade-offs and implementation details.
