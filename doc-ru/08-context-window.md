# Управление контекстным окном

Длинные диалоги накапливают сотни сообщений и в итоге превышают лимит контекста LLM. IntentReactor предоставляет несколько механизмов для управления размером истории.

---

## Конвейер обработки сообщений

Перед каждым вызовом `DefaultReACTPlanner` / `ReflexionPlanner` история сообщений проходит два упорядоченных конвейера расширений. **`LATSPlanner` эти конвейеры не использует.**

### MessageContextPreProcessor

Запускается на **полном списке сообщений сессии** до применения скользящего окна. Подходит для инжекции вводных сообщений или глобальной фильтрации категорий.

```java
@Component
public class InjectPreamblePre implements MessageContextPreProcessor {

    @Override
    public List<Message> process(List<Message> allMessages, SessionState session) {
        List<Message> result = new ArrayList<>(allMessages);
        result.add(0, Message.system("Отвечай только на русском языке."));
        return result;
    }

    @Override
    public int getOrder() { return -100; }
}
```

### MessageContextPostProcessor

Запускается на **списке сообщений в окне** после применения скользящего окна, до преобразования в типы LLM. Получает `MessageBuildContext`, который предоставляет:
- `getEvictedMessages()` — сообщения, вытесненные из окна (полезно для компрессии)
- `setCharLimit(message, limit)` — переопределение лимита символов для конкретного сообщения, учитываемое шагом обрезки

```java
@Component
public class DeduplicateSnapshotPost implements MessageContextPostProcessor {

    @Override
    public List<Message> process(List<Message> messages, MessageBuildContext ctx) {
        // оставить только последний результат take_snapshot
        // ...
        return deduped;
    }

    @Override
    public int getOrder() { return 0; }  // запуск до компрессии
}
```

Рекомендуемые значения порядка:

| Порядок | Назначение |
|---|---|
| `0` | Дедупликация снимков, нормализация контента |
| `200` | LLM-компрессия (`MessageCompressor`) |
| `Integer.MAX_VALUE - 100` | Встроенный `MessageCompressor`: `Ordered.LOWEST_PRECEDENCE - 100` |

---

## Скользящее окно

Базовый контроль: хранить только N последних сообщений.

```yaml
intent-reactor:
  planning:
    context-window:
      max-messages: 20   # по умолчанию; 0 = без ограничений
```

Когда история превышает `max-messages`, самые старые сообщения вытесняются. **Закреплённые сообщения** не вытесняются — они переставляются на исходную хронологическую позицию даже при выходе за границу окна. Это гарантирует, что исходная цель пользователя и ключевой контекст никогда не теряются.

---

## Ограничение символов для сообщений

Обрезка отдельных слишком длинных сообщений:

```yaml
intent-reactor:
  planning:
    context-window:
      max-message-chars: 8000     # по умолчанию; 0 = без ограничений
      truncation-suffix: "... [обрезано]"
```

Для сообщений, которым обоснованно нужен больший лимит (например, DOM-снимки при автоматизации браузера), зарегистрируйте `MessageContextPostProcessor` и вызовите `context.setCharLimit(message, largerLimit)` для нужных сообщений. Шаг обрезки читает эти переопределения.

---

## LLM-компрессия контекста

Когда скользящего окна недостаточно, включите компрессию на основе токенов. Старые сообщения, вышедшие за границу окна, суммируются LLM и возвращаются в историю как единое сообщение с префиксом `[ИСТОРИЯ ДИАЛОГА]`.

Встроенная реализация — `MessageCompressor`. Он автоматически регистрируется как `MessageContextPostProcessor` с порядком `Ordered.LOWEST_PRECEDENCE - 100` при включённой компрессии.

```yaml
intent-reactor:
  planning:
    context-window:
      compression:
        enabled: true
        max-tokens: 4000          # оценочный бюджет токенов
        chars-per-token: 4        # коэффициент оценки токенов
        trigger-ratio: 0.85       # сжать при estimatedTokens > max-tokens * ratio
        summary-prompt: classpath:prompts/context-compression-ru.md
```

**Как работает:**

1. После прохождения конвейера постпроцессоров `MessageCompressor` оценивает общее количество токенов: `totalChars / charsPerToken`.
2. Если `estimatedTokens > maxTokens × triggerRatio`, вытесненные сообщения из `MessageBuildContext.getEvictedMessages()` передаются LLM с промптом компрессии.
3. LLM возвращает краткое резюме.
4. Резюме вставляется на позицию 0 списка сообщений в окне как `UserMessage`.
5. Результат кешируется в `session.attributes["_contextSummary"]` с ключом по количеству вытесненных сообщений — чтобы избежать лишних вызовов LLM при повторных итерациях `plan()` в той же сессии.
6. Публикуется `ContextCompressedEvent`.

> **Примечание:** компрессия отключена по умолчанию (`enabled: false`), так как требует дополнительного вызова LLM. Включайте только для сессий с большим количеством итераций.

---

## Закрепление сообщений

Закрепить сообщение, чтобы защитить его от вытеснения и компрессии:

```java
// Закрепить следующее сообщение пользователя
session.getAttributes().put(SessionAttributeKeys.PIN_NEXT_USER_MESSAGE, true);
reactor.process(sessionId, "Важная инструкция: всегда отвечай на русском языке.");
```

Фреймворк также автоматически закрепляет:
- **Первое** сообщение каждой сессии (исходная цель пользователя).
- Сообщения, отправленные сразу после возобновления из состояния подтверждения (контекст коррекции).

---

## Полный справочник конфигурации

```yaml
intent-reactor:
  planning:
    context-window:
      max-messages: 20              # размер скользящего окна (0 = без ограничений)
      max-message-chars: 8000       # лимит символов на сообщение (0 = без ограничений)
      truncation-suffix: "... [обрезано]"
      compression:
        enabled: false
        max-tokens: 4000
        chars-per-token: 4
        trigger-ratio: 0.85
        summary-prompt: classpath:prompts/context-compression-ru.md
```

---

## ContextCompressedEvent

Публикуется при срабатывании компрессии:

```java
@EventListener
public void onCompression(ContextCompressedEvent event) {
    log.info("Сессия {} — сжато {} сообщений в {} символов",
        event.getSessionId(),
        event.getCompressedMessageCount(),
        event.getSummaryLength());
}
```
