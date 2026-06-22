# Events and Metrics

IntentReactor publishes Spring Application Events at every stage of the planning lifecycle. Micrometer metrics are collected automatically when `MeterRegistry` is on the classpath.

---

## Spring Events

All events extend `IntentReactorEvent`, which provides:
- `getSessionId()` — the session this event belongs to.
- `getOccurredAt()` — `LocalDateTime` timestamp (immutable, set at creation).

Subscribe with `@EventListener` in any Spring bean:

```java
@Component
public class PlanningAuditListener {

    @EventListener
    public void onCompleted(PlanCompletedEvent event) {
        auditLog.record(event.getSessionId(), "COMPLETED", event.getFinalText());
    }

    @EventListener
    public void onFailed(PlanFailedEvent event) {
        alertService.sendAlert("Plan failed in session " + event.getSessionId()
            + ": " + event.getReason());
    }
}
```

Events are published **synchronously** on the calling thread. Avoid heavy work in listeners; use `@Async` or delegate to a queue for non-trivial processing.

---

## Event reference

| Event | Extra fields | Published when |
|---|---|---|
| `IntentAnalysisStartedEvent` | `message` (String) | Before `IntentPreprocessor.analyze()` |
| `IntentAnalysisCompletedEvent` | `result` (IntentAnalysisResult) | After intent analysis completes |
| `PlanStartedEvent` | `goalDescription` (String) | After PlanState is initialized |
| `PlanStepStartedEvent` | `step` (PlanStep) | Before each step is processed |
| `PlanStepCompletedEvent` | `step` (PlanStep), `result` (ToolResult) | After each ACT step executes |
| `PlanCompletedEvent` | `finalText` (String) | When a DONE step is reached |
| `PlanFailedEvent` | `reason` (String) | On FAIL step, max-steps, timeout, or rejection |
| `ConfirmationRequiredEvent` | `confirmationRequest` (ConfirmationRequest) | When a risky tool pauses execution |
| `ContextCompressedEvent` | `compressedMessageCount` (int), `summaryLength` (int) | When LLM compression runs |

---

## Event usage examples

### Measure end-to-end latency

```java
@Component
public class LatencyTracker {

    private final Map<String, Instant> starts = new ConcurrentHashMap<>();

    @EventListener
    public void onStart(PlanStartedEvent e) {
        starts.put(e.getSessionId(), Instant.now());
    }

    @EventListener
    public void onEnd(PlanCompletedEvent e) {
        Instant start = starts.remove(e.getSessionId());
        if (start != null) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info("Session {} completed in {} ms", e.getSessionId(), ms);
        }
    }
}
```

### Push confirmation to a message queue

```java
@EventListener
public void onConfirmation(ConfirmationRequiredEvent event) {
    ConfirmationRequest req = event.getConfirmationRequest();
    messagingTemplate.convertAndSendToUser(
        lookupUserId(event.getSessionId()),
        "/queue/confirm",
        Map.of(
            "sessionId",   event.getSessionId(),
            "tool",        req.getToolName(),
            "description", req.getDescription(),
            "parameters",  req.getParameters()
        )
    );
}
```

### Track individual tool performance

```java
@EventListener
public void onStepCompleted(PlanStepCompletedEvent event) {
    if (event.getStep().type() == StepType.ACT) {
        String tool = event.getStep().action().toolName();
        boolean success = event.getResult().isSuccess();
        metrics.record(tool, success);
    }
}
```

---

## Micrometer metrics

When `io.micrometer:micrometer-core` (or any Micrometer registry like `micrometer-registry-prometheus`) is on the classpath, all planners are automatically wrapped with `MicrometerPlannerDecorator`.

The decorator records metrics tagged with `strategy` (the planner name: `react`, `lats`, etc.):

| Metric | Type | Description |
|---|---|---|
| `intent_reactor.plan.steps` | Counter | Number of planning iterations per session |
| `intent_reactor.plan.duration` | Timer | Time spent in `Planner.plan()` per call |

### Prometheus scrape example

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
  metrics:
    tags:
      application: my-app
```

Access metrics at `GET /actuator/prometheus`.

### Grafana query examples

```promql
# Average planning iterations per strategy
rate(intent_reactor_plan_steps_total[5m]) by (strategy)

# p99 planning latency
histogram_quantile(0.99, rate(intent_reactor_plan_duration_seconds_bucket[5m])) by (strategy)
```

---

## Default event logger

By default, `IntentReactorEventLogger` logs all events at `INFO` level using SLF4J. Disable it if you have custom listeners that already handle logging:

```yaml
intent-reactor:
  logging:
    enabled: false
```
