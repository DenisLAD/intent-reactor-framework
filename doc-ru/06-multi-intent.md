# Мультинамерения

Когда `IntentPreprocessor` обнаруживает **более одного намерения** в сообщении пользователя, фреймворк автоматически активирует стратегию мультинамерений вместо обычного одиночного цикла.

---

## Условие срабатывания

Мультинамерения активируются, когда `IntentAnalysisResult.getIntents().size() > 1`. При неопределённом результате или нулевом количестве намерений фреймворк переходит к обработке одиночного намерения независимо от настройки.

Пример сообщения, которое может породить два намерения:
> *«Проверь погоду в Берлине и узнай статус заказа ORD-456.»*

Препроцессор может вернуть:
```json
{
  "intents": [
    { "name": "weather_lookup", "confidence": 0.95, "attributes": { "city": "Berlin" } },
    { "name": "order_lookup",   "confidence": 0.92, "attributes": { "orderId": "ORD-456" } }
  ]
}
```

---

## Стратегии диспетчеризации

Настраивается через `intent-reactor.planning.multi-intent.strategy`. Каждая стратегия реализована отдельным Spring-бином и автоматически регистрируется `IntentReactorAutoConfiguration`.

| Значение | Класс | Описание |
|---|---|---|
| `sequential` (по умолчанию) | `SequentialMultiIntentStrategy` | Намерения обрабатываются по очереди в обнаруженном порядке |
| `parallel` | `ParallelMultiIntentStrategy` | Все намерения выполняются одновременно в изолированных сессиях |
| `llm-driven` | `LlmDrivenMultiIntentStrategy` | LLM определяет порядок, затем выполнение последовательное |

---

## sequential

```yaml
intent-reactor:
  planning:
    multi-intent:
      strategy: sequential
```

Намерения обрабатываются в порядке, возвращённом `IntentPreprocessor`. Каждое намерение проходит полный цикл планирования (включая вызовы инструментов). История сессии общая — результат каждого намерения виден при обработке следующего.

Если одно намерение требует **подтверждения**, выполнение приостанавливается. После `proceedAfterConfirmation()` обработка оставшихся намерений продолжается автоматически.

**Слияние результатов** — финальный текст объединяет результаты в формате:
```
[weather_lookup] В Берлине +22°C, солнечно.; [order_lookup] Заказ ORD-456 отправлен, ожидаемая доставка 01.08.2025.
```

Если хотя бы одно намерение завершилось ошибкой, итоговый ответ имеет `status=FAILED` и содержит все частичные результаты.

---

## parallel

```yaml
intent-reactor:
  planning:
    multi-intent:
      strategy: parallel
    parallel-timeout: PT60S   # таймаут для каждого намерения (по умолчанию)
```

Для каждого намерения создаётся **клон сессии** (независимый `SessionState` с производным ID вида `originalId-parallel-weatherlookup`). Все клоны выполняются одновременно через `CompletableFuture`, диспетчеризованные в бин `intentReactorParallelExecutor` (кешированный пул с daemon-потоками). Для замены пула объявите бин `ExecutorService` с именем `intentReactorParallelExecutor`.

Поведение:
- При истечении таймаута (`parallel-timeout`) `CompletableFuture` отменяется и для этого намерения фиксируется результат `FAILED`.
- Подтверждение рискованных инструментов **не поддерживается** в параллельном режиме — пауза подтверждения не может быть безопасно сериализована между конкурентными futures. Отмечайте параллельные инструменты как нерискованные или используйте sequential-режим для потоков с подтверждением.
- Результаты объединяются в порядке исходного обнаружения намерений.

---

## llm-driven

```yaml
intent-reactor:
  planning:
    multi-intent:
      strategy: llm-driven
```

Перед выполнением делается один дополнительный вызов LLM со всеми обнаруженными намерениями, и модель самостоятельно определяет оптимальный порядок (например: «сначала узнаем заказ, чтобы получить город для погоды»). Переупорядоченный список затем выполняется последовательно.

При ошибке вызова упорядочивания фреймворк переходит к сортировке намерений по уровню уверенности (наибольшая — первой).

---

## MultiIntentContext

Состояние оркестрации хранится в `session.attributes["multiIntentState"]` как объект `MultiIntentContext`:

```
MultiIntentContext {
    List<Intent> pendingIntents      // намерения, ожидающие обработки
    List<Intent> completedIntents    // уже обработанные намерения
    Intent currentIntent             // текущее намерение
    String strategy                  // "sequential" | "parallel" | "llm-driven"
    Map<String, ReactorResponse> results  // имяНамерения → ответ (в порядке вставки)
}
```

Объект сохраняется между итерациями планирования и паузами подтверждения, поэтому фреймворк всегда знает, где остановился.

---

## Пример: обработка объединённого ответа

```java
ReactorResponse response = reactor.process("session-1",
    "Проверь погоду в Берлине и узнай статус заказа ORD-456");

if (response.getStatus() == PlanStatus.COMPLETED) {
    // finalText содержит конкатенацию обоих результатов
    System.out.println(response.getFinalText());

    // отдельные вызовы инструментов в списке actions
    response.getActions().forEach(action ->
        System.out.printf("Инструмент: %s → %s%n", action.getToolName(), action.getResult()));
}
```

---

## Конфигурация

```yaml
intent-reactor:
  planning:
    multi-intent:
      strategy: sequential   # sequential | parallel | llm-driven
    parallel-timeout: PT60S  # только для parallel
```
