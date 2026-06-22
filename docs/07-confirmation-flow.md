# Confirmation Flow

Risky tools (those where `isRisky()` returns `true`) pause execution and ask the caller to explicitly approve the action before it is executed. This prevents irreversible operations — payments, deletions, emails — from running without human oversight.

---

## Trigger conditions

Confirmation is required when **both** of the following are true:
1. `tool.isRisky() == true`
2. `intent-reactor.planning.autonomous=false` (the default)

Set `autonomous=true` to skip all confirmation prompts globally. You can also override `ConfirmationManager` to implement custom logic per tool or per user role.

---

## Pause flow

When the planner selects a risky tool, the following happens inside `IntentReactorServiceImpl`:

1. `ConfirmationManager.buildRequest(step)` creates a `ConfirmationRequest`:
   ```
   ConfirmationRequest {
       String actionId         // unique identifier for this request
       String toolName         // e.g. "cancel_order"
       String description      // human-readable prompt, e.g. "Cancel order ORD-123?"
       Map<String,Object> parameters  // planner's intended parameters
   }
   ```

2. Session state is mutated:
   - `PlanState.status = AWAITING_CONFIRMATION`
   - `attributes["pendingStep"]` = the paused `PlanStep` (serialized as Map)
   - `attributes["confirmationRequestedAt"]` = `LocalDateTime.now().toString()`

3. Session is persisted via `SessionStore.save()`.

4. `ConfirmationRequiredEvent` is published (useful for pushing to a UI or message queue).

5. `ReactorResponse.awaitingConfirmation(sessionId, request)` is returned to the caller.

---

## Caller responsibility

```java
ReactorResponse response = reactor.process("session-abc", "Cancel order ORD-123");

if (response.getStatus() == PlanStatus.AWAITING_CONFIRMATION) {
    ConfirmationRequest req = response.getConfirmationRequest();

    // Show req.getDescription() to the user and collect their decision
    boolean approved = ui.confirm(req.getDescription());

    ReactorResponse resumed = reactor.proceedAfterConfirmation(
        response.getSessionId(),
        approved ? ConfirmationResult.approve() : ConfirmationResult.reject()
    );
}
```

---

## Resume flow (proceedAfterConfirmation)

`proceedAfterConfirmation(sessionId, confirmation)`:

1. **Load session** — throws `IllegalArgumentException` if not found.

2. **Verify state** — throws `IllegalStateException` if `status != AWAITING_CONFIRMATION`.

3. **Timeout check** — reads `"confirmationRequestedAt"` from attributes and compares against `planning.confirmation-timeout` (default `PT30M`). If expired:
   - Sets status to `FAILED`.
   - Publishes `PlanFailedEvent("Confirmation request expired")`.
   - Returns `ReactorResponse.failed(...)`.

4. **Rejection** — if `!confirmation.isApproved()`:
   - Sets status to `FAILED`.
   - Publishes `PlanFailedEvent("User rejected action")`.
   - Returns `ReactorResponse.failed("Action was rejected by user")`.

5. **Modified parameters** — if `confirmation.getModifiedParameters()` is non-empty, stores them under `"pendingModifiedParameters"`. When the tool executes, these replace the planner's original parameters.

6. **Resume** — sets status back to `RUNNING`, saves session, calls `continueExecution()`. The pending step is executed directly (no re-planning).

7. **Multi-intent continuation** — if this was part of a multi-intent flow with remaining pending intents, those are automatically continued after the confirmed step completes.

---

## ConfirmationResult factory methods

```java
// Simple approval
ConfirmationResult.approve()

// Rejection
ConfirmationResult.reject()

// Approval with modified parameters (e.g., user changes the amount)
ConfirmationResult.approveWithModifications(Map.of("amount", 50, "currency", "USD"))
```

---

## Confirmation timeout

```yaml
intent-reactor:
  planning:
    confirmation-timeout: PT30M   # ISO-8601 duration; default 30 minutes
```

The clock starts when the `AWAITING_CONFIRMATION` response is returned. If `proceedAfterConfirmation()` is not called within this window, the next call will receive a `FAILED` response.

---

## Custom ConfirmationManager

```java
@Bean
@Primary
public ConfirmationManager confirmationManager(IntentReactorProperties props) {
    return new ConfirmationManager() {
        @Override
        public boolean needsConfirmation(Tool tool) {
            // Skip confirmation for internal service accounts
            return tool.isRisky() && !isServiceAccount();
        }

        @Override
        public ConfirmationRequest buildRequest(PlanStep step) {
            return new ConfirmationRequest(
                UUID.randomUUID().toString(),
                step.action().toolName(),
                "Please confirm: " + step.description(),
                step.action().parameters()
            );
        }
    };
}
```

---

## REST endpoint example

```java
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final IntentReactorService reactor;

    @PostMapping("/{sessionId}")
    public ResponseEntity<ChatResponse> chat(@PathVariable String sessionId,
                                             @RequestBody String message) {
        ReactorResponse r = reactor.process(sessionId, message);
        return ResponseEntity.ok(toDto(r));
    }

    @PostMapping("/{sessionId}/confirm")
    public ResponseEntity<ChatResponse> confirm(@PathVariable String sessionId,
                                                @RequestBody ConfirmDto dto) {
        ConfirmationResult result = dto.approved()
            ? (dto.modifications() != null
                ? ConfirmationResult.approveWithModifications(dto.modifications())
                : ConfirmationResult.approve())
            : ConfirmationResult.reject();

        ReactorResponse r = reactor.proceedAfterConfirmation(sessionId, result);
        return ResponseEntity.ok(toDto(r));
    }
}
```
