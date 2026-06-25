# События и метрики

IntentReactor публикует Spring Application Events на каждом этапе жизненного цикла планирования. Метрики Micrometer собираются автоматически при наличии `MeterRegistry` в classpath.

---

## Spring Events

Все события наследуют от `IntentReactorEvent`, который предоставляет:
- `getSessionId()` — сессия, к которой относится событие.
- `getOccurredAt()` — метка времени `LocalDateTime` (неизменяемая, устанавливается при создании).

Подписывайтесь через `@EventListener` в любом Spring-бине:

```java
@Component
public class PlanningAuditListener {

    @EventListener
    public void onCompleted(PlanCompletedEvent event) {
        auditLog.record(event.getSessionId(), "COMPLETED", event.getFinalText());
    }

    @EventListener
    public void onFailed(PlanFailedEvent event) {
        alertService.sendAlert("Планирование завершилось ошибкой в сессии "
            + event.getSessionId() + ": " + event.getReason());
    }
}
```

События публикуются **синхронно** в вызывающем потоке. Избегайте тяжёлых операций в слушателях — используйте `@Async` или делегирование в очередь для нетривиальной обработки.

---

## Справочник событий

| Событие | Дополнительные поля | Когда публикуется |
|---|---|---|
| `IntentAnalysisStartedEvent` | `message` (String) | Перед `IntentPreprocessor.analyze()` |
| `IntentAnalysisCompletedEvent` | `result` (IntentAnalysisResult) | После завершения анализа намерений |
| `PlanStartedEvent` | `goalDescription` (String) | После инициализации PlanState |
| `PlanStepStartedEvent` | `step` (PlanStep) | Перед обработкой каждого шага |
| `PlanStepCompletedEvent` | `step` (PlanStep), `result` (ToolResult) | После выполнения каждого шага ACT |
| `PlanCompletedEvent` | `finalText` (String) | При достижении шага DONE |
| `PlanFailedEvent` | `reason` (String) | При шаге FAIL, превышении max-steps, таймауте или отклонении |
| `ConfirmationRequiredEvent` | `confirmationRequest` (ConfirmationRequest) | При паузе из-за рискованного инструмента |
| `ContextCompressedEvent` | `compressedMessageCount` (int), `summaryLength` (int) | При срабатывании LLM-компрессии |

---

## Примеры использования событий

### Измерение сквозной задержки

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
            log.info("Сессия {} завершена за {} мс", e.getSessionId(), ms);
        }
    }
}
```

### Отправка подтверждения в очередь сообщений

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

### Мониторинг производительности инструментов

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

## Метрики Micrometer

При наличии `io.micrometer:micrometer-core` (или любого реестра Micrometer, например `micrometer-registry-prometheus`) все планировщики автоматически оборачиваются `MicrometerPlannerDecorator`.

Декоратор записывает метрики с тегом `strategy` (имя планировщика: `react`, `lats` и т.д.):

| Метрика | Тип | Описание |
|---|---|---|
| `intent_reactor.plan.steps` | Counter | Количество итераций планирования на сессию |
| `intent_reactor.plan.duration` | Timer | Время выполнения `Planner.plan()` на вызов |

### Пример настройки Prometheus

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

Доступ к метрикам: `GET /actuator/prometheus`.

### Примеры запросов Grafana

```promql
# Среднее количество итераций по стратегиям
rate(intent_reactor_plan_steps_total[5m]) by (strategy)

# p99 задержки планирования
histogram_quantile(0.99, rate(intent_reactor_plan_duration_seconds_bucket[5m])) by (strategy)
```

---

## Встроенный логгер событий

По умолчанию `IntentReactorEventLogger` логирует все события на уровне `INFO` через SLF4J. Отключите его, если у вас есть кастомные слушатели, уже обрабатывающие логирование:

```yaml
intent-reactor:
  logging:
    enabled: false
```
