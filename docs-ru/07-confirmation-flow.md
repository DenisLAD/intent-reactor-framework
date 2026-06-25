# Поток подтверждения

## Условия срабатывания

Выполнение приостанавливается и требует подтверждения пользователя, когда одновременно выполняются все три условия:
1. `tool.isRisky()` возвращает `true`.
2. `autonomous: false` (значение по умолчанию).
3. `ConfirmationManager.needsConfirmation()` возвращает `true` (логика по умолчанию: всегда, если пункты 1 и 2 выполнены).

---

## Пауза: что происходит

1. Шаг `ACT` сохраняется в `session.attributes["pendingStep"]`.
2. Публикуется `ConfirmationRequiredEvent` с объектом `ConfirmationRequest`.
3. Метод `process()` возвращает `ReactorResponse` со статусом `AWAITING_CONFIRMATION`.
4. Сессия сохраняется в `SessionStore` с незавершённым состоянием.
5. Вызывающий код должен доставить `ConfirmationRequest` пользователю и дождаться его решения.

---

## Поля ConfirmationRequest

| Поле | Тип | Описание |
|---|---|---|
| `sessionId` | String | Идентификатор сессии |
| `toolName` | String | Имя инструмента, ожидающего подтверждения |
| `description` | String | Человекочитаемое описание действия |
| `parameters` | Map\<String, Object\> | Параметры вызова инструмента |
| `requestedAt` | LocalDateTime | Метка времени приостановки |
| `expiresAt` | LocalDateTime | `requestedAt + confirmation-timeout` |

---

## Ответственность вызывающего кода

```java
// 1. Инициировать запрос
ReactorResponse response = reactor.process(sessionId, userMessage);

if (response.getStatus() == ReactorStatus.AWAITING_CONFIRMATION) {
    ConfirmationRequest req = response.getConfirmationRequest();

    // 2. Показать пользователю (пример для REST)
    // GET /confirm/{sessionId} → возвращает req

    // 3. Получить решение пользователя и возобновить
    // POST /confirm/{sessionId} с телом решения → вызывает:
    ReactorResponse finalResponse = reactor.proceedAfterConfirmation(sessionId, result);
}
```

---

## ConfirmationResult: варианты

```java
// Пользователь одобрил — выполнить с оригинальными параметрами
ConfirmationResult.approve()

// Пользователь отклонил — шаг пропускается, планировщик уведомляется
ConfirmationResult.reject("Не отправлять email без дополнительной проверки.")

// Пользователь одобрил с изменёнными параметрами
ConfirmationResult.approveWithModifications(Map.of(
    "recipient", "другой@пример.com",
    "subject", "[ПРОВЕРЕНО] " + originalSubject
))

// Таймаут истёк — аналогично reject
ConfirmationResult.timeout()
```

---

## Поток возобновления

```
proceedAfterConfirmation(sessionId, result)
        │
        ├── approve / approveWithModifications
        │       │
        │       ├── Извлечь pendingStep из session.attributes
        │       ├── При approveWithModifications: обновить параметры шага
        │       ├── Выполнить инструмент
        │       ├── Добавить результат в session.messages
        │       └── Продолжить цикл ReACT → вернуть финальный ReactorResponse
        │
        └── reject / timeout
                │
                ├── Добавить SYSTEM-сообщение об отклонении
                └── Продолжить цикл ReACT (планировщик увидит отклонение и скорректирует план)
```

---

## Конфигурация таймаута

```yaml
intent-reactor:
  planning:
    confirmation-timeout: PT30M   # сессия хранится в состоянии ожидания 30 минут
    autonomous: false             # установите true, чтобы отключить подтверждения
```

---

## Кастомный ConfirmationManager

```java
@Component
@Primary
public class RoleBasedConfirmationManager implements ConfirmationManager {

    @Override
    public boolean needsConfirmation(Tool tool, ToolInput input, SessionState session) {
        // Администраторы не нуждаются в подтверждении
        String role = (String) session.getAttributes().get("userRole");
        return tool.isRisky() && !"admin".equals(role);
    }

    @Override
    public ConfirmationRequest buildRequest(Tool tool, ToolInput input, SessionState session) {
        return ConfirmationRequest.builder()
            .sessionId(session.getId())
            .toolName(tool.getName())
            .description(tool.getDescription())
            .parameters(input.getParameters())
            .build();
    }
}
```

---

## Пример REST-эндпоинта

```java
@RestController
@RequiredArgsConstructor
public class ConfirmationController {

    private final IntentReactorService reactor;

    @GetMapping("/confirm/{sessionId}")
    public ConfirmationRequest getPendingConfirmation(@PathVariable String sessionId) {
        SessionState session = reactor.getSessionState(sessionId);
        PlanStep pending = (PlanStep) session.getAttributes().get("pendingStep");
        if (pending == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return buildConfirmationRequest(pending, session);
    }

    @PostMapping("/confirm/{sessionId}/approve")
    public ReactorResponse approve(@PathVariable String sessionId) {
        return reactor.proceedAfterConfirmation(sessionId, ConfirmationResult.approve());
    }

    @PostMapping("/confirm/{sessionId}/reject")
    public ReactorResponse reject(@PathVariable String sessionId,
                                  @RequestBody String reason) {
        return reactor.proceedAfterConfirmation(sessionId, ConfirmationResult.reject(reason));
    }
}
```
