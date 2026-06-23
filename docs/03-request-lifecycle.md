# Request Lifecycle

This page traces every step that occurs when you call `IntentReactorService.process(sessionId, message)`.

---

## Phase 1 — Session setup

1. **Load or create session**
   - `SessionStore.findById(sessionId)` is called.
   - If not found, a new `SessionState` is created with the given ID.

2. **Add user message**
   - The first message in a session is always **pinned** (`Message.pinnedUser()`). Pinned messages are never evicted from the context window and are excluded from LLM compression.
   - If `SessionAttributeKeys.PIN_NEXT_USER_MESSAGE` (`"_pinNextUserMessage"`) is set to `true` in session attributes, that attribute is consumed and the message is pinned regardless of position.
   - All other messages are added with `Message.user()`.

3. **Persist session** — `SessionStore.save(session)`.

---

## Phase 2 — Intent analysis

4. **Publish `IntentAnalysisStartedEvent`** (carries the raw message).

5. **`IntentPreprocessor.analyze(message, session, context)`**
   - The LLM classifies the message into one or more `Intent` objects.
   - Returns `IntentAnalysisResult` with intents, entities, uncertainty flag, and a reasoning suggestion.

6. **Publish `IntentAnalysisCompletedEvent`** (carries the full result).

---

## Phase 3 — Plan state initialization

7. **Reset PlanState**
   - A new `PlanState` is created with `goalDescription = reasoningSuggestion ?? message`.
   - Completed steps from the previous plan (if any) are preserved so the planner has context.
   - Status is set to `RUNNING`.

8. **Publish `PlanStartedEvent`** (carries the goal description).

---

## Phase 4 — Multi-intent dispatch

9. **Guard check**
   - If `intent.isUncertain() || !intent.hasIntents()`, treat as a single intent and skip dispatch.

10. **Multi-intent check**
    - If `intents.size() > 1`, call `executeMultiIntent()` (see [06-multi-intent.md](06-multi-intent.md)).
    - Otherwise, continue with the main loop.

---

## Phase 5 — ReACT iteration loop

The following steps repeat up to `planning.max-steps` times.

### 5a. Resume after confirmation (if applicable)

If `"pendingStep"` exists in session attributes (set during a previous confirmation pause), execute it directly — skipping re-planning to avoid an infinite confirmation loop:
- Remove `"pendingStep"` and `"confirmationRequestedAt"` from attributes.
- Execute the tool, record the result.
- Add a `[TOOL_RESULT]` or `[TOOL_ERROR]` SYSTEM message.
- Publish `PlanStepCompletedEvent`.

### 5b. Plan

11. **Reconstruct current intent** from `"originalIntent"` attribute or from the goal description.

12. **Call `Planner.plan(session, intent)`**
    - For `DefaultReACTPlanner` / `ReflexionPlanner`, the planner runs the message history through two extension pipelines before sending it to the LLM (see [08-context-window.md](08-context-window.md)):
      1. **`MessageContextPreProcessor`** chain — on the full session list (before sliding window)
      2. Sliding window + pinned-message re-insertion
      3. **`MessageContextPostProcessor`** chain — on the windowed list (compression, deduplication, etc.)
    - `LATSPlanner` does not use these pipelines.
    - Returns a `Plan` with one or more `PlanStep` objects.

### 5c. Execute plan steps

For each step in `plan.steps()`:

**`StepType.DONE`** — terminal success:
- Set `PlanState.status = COMPLETED`.
- Add an ASSISTANT message with the final text.
- Persist session.
- Publish `PlanCompletedEvent`.
- Return `ReactorResponse.completed(sessionId, finalText, actions)`.

**`StepType.FAIL`** — terminal failure:
- Set `PlanState.status = FAILED`.
- Persist session.
- Publish `PlanFailedEvent`.
- Return `ReactorResponse.failed(sessionId, reason)`.

**`StepType.REASON`** — internal thought:
- Publish `PlanStepStartedEvent`.
- Append to `session.attributes["thoughts"]` list (not to message history — keeps LLM context clean).

**`StepType.OBSERVE` / `StepType.REFLECT`** — observations:
- Publish `PlanStepStartedEvent`.
- Append a SYSTEM message to session history.

**`StepType.ACT`** — tool call:
- Publish `PlanStepStartedEvent`.
- **Confirmation check** (see [07-confirmation-flow.md](07-confirmation-flow.md)):
  - If `step.requiresConfirmation()` is true:
    - Build `ConfirmationRequest` via `ConfirmationManager`.
    - Set `PlanState.status = AWAITING_CONFIRMATION`.
    - Save `"pendingStep"` and `"confirmationRequestedAt"` in attributes.
    - Persist session.
    - Publish `ConfirmationRequiredEvent`.
    - Return `ReactorResponse.awaitingConfirmation(sessionId, request)`.
    - **Execution pauses here.**
- **Tool execution**:
  - Check for `"pendingModifiedParameters"` (set via `ConfirmationResult.approveWithModifications()`); replace action parameters if present.
  - Resolve `Tool` by name via `ToolProvider.getAvailableTools()`.
  - Call `tool.execute(new ToolInput(params, sessionId))`.
  - Any exception is caught and returned as `ToolResult.error(message)`.
- Record `PerformedAction`.
- Append `[TOOL_RESULT] toolName: {data}` or `[TOOL_ERROR] toolName: {error}` as a SYSTEM message.
- Mark step as completed in `PlanState`.
- Publish `PlanStepCompletedEvent`.
- **Break the inner step loop** — the outer loop calls `Planner.plan()` again with the updated history.

### 5d. Max steps exceeded

If the outer loop completes all `max-steps` iterations without reaching `DONE` or `FAIL`:
- Set `PlanState.status = FAILED`.
- Persist session.
- Publish `PlanFailedEvent("Max steps exceeded")`.
- Return `ReactorResponse.failed(sessionId, "Maximum number of steps exceeded")`.

---

## Phase 6 — Response assembly

Every terminal path builds a `ReactorResponse` with:

| Field | Source |
|---|---|
| `sessionId` | Input parameter |
| `status` | `PlanStatus.COMPLETED`, `FAILED`, or `AWAITING_CONFIRMATION` |
| `finalText` | DONE step description, failure reason, or `null` |
| `actions` | All `PerformedAction` objects collected during the loop |
| `reasoningSteps` | Built from SYSTEM messages in `session.getMessages()` |
| `confirmationRequest` | Non-null only when `AWAITING_CONFIRMATION` |

---

## Sequence diagram (simplified)

```
Caller          Service           Preprocessor      Planner        Tool
  │                │                   │               │              │
  │─process()─────►│                   │               │              │
  │                │─analyze()─────────►               │              │
  │                │◄──IntentResult────┘               │              │
  │                │                                   │              │
  │                │─────────[loop up to max-steps]────►              │
  │                │                                   │─plan()──────►│  (LLM call)
  │                │                                   │◄──Plan───────┘
  │                │                                   │              │
  │                │─execute(ToolInput)────────────────────────────── ►│
  │                │◄──ToolResult──────────────────────────────────────┘
  │                │                                   │              │
  │                │─────────[loop again if not DONE/FAIL]────────────│
  │                │                                   │              │
  │◄──Response────┘
```
