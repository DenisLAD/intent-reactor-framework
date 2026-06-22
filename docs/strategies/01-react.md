# ReACT Strategy

**Module:** `intent-reactor-core` (always available)
**Strategy value:** `react`

---

## Summary

ReACT (Reason + Act) is IntentReactor's default strategy. It drives an iterative Thought → Action → Observation loop: the LLM reasons about what to do next, calls a tool, observes the result, and continues until it either completes the task or hits the step limit.

---

## How It Works

1. The system prompt is built from the active `PromptContextProvider` beans and injected into the message history.
2. The LLM is called with the full conversation history (subject to context-window limits).
3. The LLM responds with a `PlanStep` expressed as a JSON tool call. Step types:
   - `REASON` — intermediate thought (no tool execution, loop continues).
   - `ACT` — invoke a named tool with parameters.
   - `DONE` — final answer is ready; the `output` field becomes the response.
   - `FAIL` — the task cannot be completed; `output` contains the reason.
4. For `ACT` steps: if the tool `isRisky()` and `autonomous=false`, execution pauses (`AWAITING_CONFIRMATION`). Otherwise the tool is executed, the `ToolResult` is added to history as a SYSTEM message, and the loop continues.
5. The loop repeats from step 2 until `DONE`, `FAIL`, or `max-steps` is reached.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: react
    max-steps: 50          # max iterations
    autonomous: false      # set true to skip confirmations
```

No additional strategy-specific properties.

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| System prompt | `prompts/react-system.md` | Instructs the LLM on the ReACT loop format and available tools |

The framework injects the tool list and extra context (from `PromptContextProvider` beans) into the template at runtime.

---

## When to Use

- **Default choice** for any tool-using agent.
- Well-defined tasks where tool outputs provide enough signal to proceed.
- Short-to-medium sessions (< 30 steps).
- Building block for `reflexion` and `lats` — both extend ReACT.

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: react
    max-steps: 30
    autonomous: true   # CI/batch environments
```

```java
String result = intentReactorService.process("session-1",
    "Read the file config.yaml and summarize its contents.");
// → Runs: read_file("config.yaml") → REASON → DONE
```
