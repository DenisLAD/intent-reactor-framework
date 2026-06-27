# Planning Strategies — Overview

IntentReactor ships 18 planning strategies, from simple chain-of-thought prompting to multi-trajectory tree search. Strategies are selected at startup via a single property.

---

## Selecting a strategy

```yaml
intent-reactor:
  planning:
    strategy: react   # change this value
```

All 18 strategies share the same `IntentReactorService.process()` API. Changing the strategy requires no code changes — only the `application.yml` entry and, for non-core strategies, the corresponding Maven module.

---

## Strategy catalogue

| Value | Strategy Name | Category | Module | Best For |
|---|---|---|---|---|
| `react` | ReACT | Iterative tool-use | core | Most workloads — general purpose |
| `reflexion` | Reflexion | Iterative + self-critique | core | Tasks where first attempt often fails |
| `lats` | LATS | Tree search + simulation | core | Complex tasks requiring exploration |
| `cot` | Chain-of-Thought | Single-shot reasoning | strategies | Analytical, no tool access needed |
| `zero-shot-cot` | Zero-shot CoT | Single-shot reasoning | strategies | Quick analysis without examples |
| `step-back` | Step-Back | Generalization | strategies | Domain-specific Q&A, expert knowledge |
| `reflection` | Reflection | Single-shot self-critique | strategies | Improving response quality post-hoc |
| `self-ask` | Self-Ask | Decomposition | strategies | Multi-hop factual questions |
| `least-to-most` | Least-to-Most | Progressive decomposition | strategies | Compositional / educational tasks |
| `plan-and-solve` | Plan-and-Solve | Two-phase planning | strategies | Structured multi-step plans |
| `tot` | Tree of Thoughts | Parallel exploration | strategies | Creative problems, open-ended reasoning |
| `got` | Graph of Thoughts | Graph aggregation | strategies | Summarization, merge, ranking tasks |
| `self-discover` | Self-Discover | Meta-cognition | strategies | Novel tasks with no obvious structure |
| `storm` | STORM | Expert-perspective synthesis | strategies | Long-form reports, comprehensive topics |
| `retreval` | ReTreVal | Tree search + validation | strategies | Complex multi-step tasks requiring backtracking |
| `map` | MAP (Modular Agentic Planner) | Multi-module orchestration | strategies | Tasks needing structured progress monitoring |
| `htp` | HTP (HyperTree Planning) | Hierarchical decomposition | strategies | Goals with clear subgoal structure and constraints |
| `knowagent` | KnowAgent | Knowledge-augmented ReACT | strategies | Tool-heavy tasks with complex precondition logic |

---

## Modules

**`intent-reactor-core`** — always included via the starter. Provides: `react`, `reflexion`, `lats`.

**`intent-reactor-strategies`** — add explicitly for the remaining 15 strategies:

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-strategies</artifactId>
    <version>0.1.14</version>
</dependency>
```

---

## Architecture: decorator vs standalone

**Core strategies** (`react`, `reflexion`, `lats`) implement the full `Planner` interface and manage the ReACT iteration loop themselves.

**Strategies module** planners fall into two sub-types:
- **Decorators over `react`** — run a preprocessing phase that informs the goal before delegating to the underlying ReACT loop (`cot`, `zero-shot-cot`, `step-back`, `reflection`, `self-discover`, `knowagent`).
- **Standalone planners** — manage their own tool-execution loop (`self-ask`, `least-to-most`, `plan-and-solve`, `tot`, `got`, `storm`, `retreval`, `map`, `htp`).

All 15 strategies in the strategies module support tool access.

```
Request
   │
   ├── [strategies module planner] (preprocessing)
   │        │
   │        └── [react planner] (tool execution loop)
   │
   └── [core planner] (react | reflexion | lats — self-contained)
```

---

## Micrometer wrapping

When `io.micrometer:micrometer-core` is on the classpath, every planner is automatically wrapped with `MicrometerPlannerDecorator` at startup. Metrics are tagged with the `strategy` value (e.g., `strategy=lats`). See [Events and Metrics](../12-events-and-metrics.md) for details.

---

## Choosing a strategy

Use this decision tree:

```
Do you need tool access?
  ├── No → consider cot / zero-shot-cot / step-back / reflection
  └── Yes
        ├── Simple, well-defined tasks → react
        ├── Tasks that benefit from self-critique → reflexion
        ├── Tasks requiring exploration of options → lats / tot / got / retreval
        ├── Multi-hop factual questions → self-ask
        ├── Progressive decomposition → least-to-most / plan-and-solve
        ├── Hierarchical goals with constraints → htp
        ├── Tasks needing structured progress monitoring → map
        ├── Tool-heavy tasks with precondition logic → knowagent
        ├── Novel / meta-cognitive tasks → self-discover
        └── Long-form reports / synthesis → storm
```

Strategy docs: [react](01-react.md) · [reflexion](02-reflexion.md) · [lats](03-lats.md) · [cot](04-cot.md) · [zero-shot-cot](05-zero-shot-cot.md) · [step-back](06-step-back.md) · [reflection](07-reflection.md) · [self-ask](08-self-ask.md) · [least-to-most](09-least-to-most.md) · [plan-and-solve](10-plan-and-solve.md) · [tot](11-tree-of-thoughts.md) · [got](12-graph-of-thoughts.md) · [self-discover](13-self-discover.md) · [storm](14-storm.md) · [retreval](15-retreval.md) · [map](16-map.md) · [htp](17-htp.md) · [knowagent](18-knowagent.md)
