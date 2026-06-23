# IntentReactor

![Java](https://img.shields.io/badge/Java-17+-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-6DB33F?logo=spring&logoColor=white)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)
[![MvnRepository](https://badges.mvnrepository.com/badge/com.intentreactor/intent-reactor-mcp-server/badge.svg?label=MvnRepository)](https://mvnrepository.com/artifact/com.intentreactor/intent-reactor-mcp-server)

**IntentReactor** — это Spring Boot Starter библиотека, которая добавляет в любое Spring Boot приложение LLM-управляемый анализ намерений и автономное планирование действий. Библиотека реализует паттерн [ReACT](https://arxiv.org/abs/2210.03629) (Reason + Act) и включает 14 взаимозаменяемых стратегий планирования — от классической цепочки мыслей до поиска по дереву Монте-Карло — переключаемых одним свойством конфигурации.

---

## Возможности

- **Цикл ReACT** — итеративный цикл Мысль → Действие → Наблюдение, работающий с любым `ChatClient` из Spring AI
- **14 встроенных стратегий планирования** — ReACT, Reflexion, LATS (MCTS), CoT, Zero-Shot CoT, Step-Back, Tree-of-Thoughts, Graph-of-Thoughts, STORM, Self-Ask, Least-to-Most, Plan-and-Solve, Reflection, Self-Discover
- **Обработка множественных намерений** — автоматическая декомпозиция составных запросов на последовательные, параллельные или упорядоченные LLM-ом подпланы
- **Подтверждение рискованных действий** — опасные инструменты приостанавливают выполнение и возвращают `AWAITING_CONFIRMATION`; продолжение — через `proceedAfterConfirmation()`
- **Подключаемые хранилища сессий** — out-of-the-box реализации: in-memory, файловая система, JDBC, JPA
- **RAG-интеграция** — инструмент `knowledge_search` с поддержкой in-memory, файловых, JDBC и векторных источников знаний
- **Динамические JavaScript-инструменты** — определение инструментов прямо во время работы приложения в виде JS-скриптов, выполняемых в изолированной среде Rhino с ограничением доступных классов и тайм-аутом
- **Поддержка MCP** — потребление удалённых MCP-серверов как локальных инструментов (транспорты SSE и STDIO); экспорт инструментов и планировщиков IntentReactor в виде MCP-сервера
- **Управление контекстным окном** — скользящее окно сообщений, дедупликация снимков DOM, ограничения длины сообщений и опциональное LLM-сжатие истории диалога
- **Spring Events** — `PlanStartedEvent`, `PlanStepCompletedEvent`, `PlanFailedEvent` и другие
- **Метрики Micrometer** — прозрачный декоратор для любого планировщика, фиксирующий количество шагов и задержки

---

## Требования

| Зависимость | Версия |
|---|---|
| Java | 17+ |
| Spring Boot | 3.5+ |
| Spring AI | 1.1+ |

---

## Быстрый старт

### 1. Подключите стартер

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-spring-boot-starter</artifactId>
    <version>0.1.12</version>
</dependency>
```

Добавьте провайдер Spring AI (например, OpenAI):

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

### 2. Настройте конфигурацию

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o

intent-reactor:
  planning:
    strategy: react    # react | reflexion | lats | cot | tot | got | storm | …
    max-steps: 10
    autonomous: false  # true = пропустить подтверждение для рискованных инструментов
```

### 3. Реализуйте инструмент

Любой Spring-бин, реализующий `Tool`, обнаруживается автоматически:

```java
@Component
public class OrderLookupTool implements Tool {

    @Override
    public String getName() { return "order_lookup"; }

    @Override
    public String getDescription() {
        return "Ищет заказ по идентификатору и возвращает его статус и дату доставки.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "orderId", Map.of("type", "string", "description", "Идентификатор заказа")
            ),
            "required", List.of("orderId")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String id = (String) input.getParameters().get("orderId");
        // ... логика поиска ...
        return ToolResult.ok(Map.of("status", "отправлен", "eta", "2025-07-01"));
    }

    @Override
    public boolean isRisky() { return false; }
}
```

### 4. Обрабатывайте сообщения пользователя

```java
@Autowired
IntentReactorService reactor;

// Разовый запрос без сессии
ReactorResponse r = reactor.process("Какой статус заказа ORD-123?", null);
System.out.println(r.getFinalText());

// Диалоговый режим с сохранением истории
ReactorResponse r1 = reactor.process("session-42", "Найди заказ ORD-123");
ReactorResponse r2 = reactor.process("session-42", "Теперь отмени его");

// Обработка подтверждения рискованного действия
if (r2.getStatus() == PlanStatus.AWAITING_CONFIRMATION) {
    ConfirmationRequest req = r2.getConfirmationRequest();
    boolean approved = ui.confirm(req.getDescription());
    ReactorResponse resumed = reactor.proceedAfterConfirmation(
        r2.getSessionId(),
        approved ? ConfirmationResult.approve() : ConfirmationResult.reject()
    );
}
```

---

## Структура модулей

```
intent-reactor-api                  Чистые интерфейсы и DTO (без зависимости на Spring)
intent-reactor-core                 Реализации по умолчанию + Spring AutoConfiguration
intent-reactor-spring-boot-starter  Тонкий стартер-входная точка

Опциональные дополнения:
├── intent-reactor-session-jdbc     SessionStore на базе JDBC
├── intent-reactor-session-jpa      SessionStore на базе JPA
├── intent-reactor-tool-commons     Готовые инструменты (калькулятор, веб-запросы, файлы, …)
├── intent-reactor-tool-dynamic     Динамические инструменты на JavaScript через Rhino
├── intent-reactor-rag              RAG KnowledgeSource + инструмент knowledge_search
├── intent-reactor-mcp-client       Потребление удалённых MCP-серверов как локальных инструментов
├── intent-reactor-mcp-server       Экспорт IntentReactor как MCP-сервера
└── intent-reactor-strategies       14 дополнительных стратегий (CoT, ToT, GoT, STORM, …)
```

---

## Стратегии планирования

Выбор через свойство `intent-reactor.planning.strategy`:

| Стратегия | Значение | Описание |
|---|---|---|
| ReACT *(по умолчанию)* | `react` | Итеративный цикл Мысль → Действие → Наблюдение |
| Reflexion | `reflexion` | Добавляет словесную саморефлексию после ошибок инструментов |
| LATS | `lats` | Поиск Монте-Карло по дереву возможных действий |
| Chain-of-Thought | `cot` | Генерирует цепочку рассуждений перед действием |
| Zero-Shot CoT | `zero-shot-cot` | Добавляет «Давай думать шаг за шагом» в промпт |
| Step-Back | `step-back` | Формулирует более абстрактный вопрос перед решением |
| Reflection | `reflection` | Итеративная критика и пересмотр плана |
| Self-Ask | `self-ask` | Декомпозиция на подвопросы с последовательными ответами |
| Least-to-Most | `least-to-most` | Решает самые простые подзадачи первыми |
| Plan-and-Solve | `plan-and-solve` | Явная фаза планирования перед исполнением |
| Tree of Thoughts | `tot` | Исследование ветвящихся путей рассуждений |
| Graph of Thoughts | `got` | Объединение и оценка узлов мыслей в графе |
| Self-Discover | `self-discover` | Выбор и адаптация модулей рассуждений в рантайме |
| STORM | `storm` | Многоперспективное исследование и синтез |

---

## Хранилища сессий

| Значение | Описание |
|---|---|
| `in-memory` *(по умолчанию)* | Хранится в памяти; данные теряются при перезапуске — идеально для разработки |
| `filesystem` | Сериализуется в JSON-файлы в настраиваемой директории |
| `jdbc` | Одна таблица `intent_reactor_sessions`; нужен модуль `intent-reactor-session-jdbc` |
| `jpa` | JPA-сущность; нужен модуль `intent-reactor-session-jpa` |

```yaml
intent-reactor:
  session:
    store: jdbc
    jdbc:
      table-name: intent_reactor_sessions
```

---

## RAG-модуль

Подключите `intent-reactor-rag` и включите хотя бы один источник знаний:

```yaml
intent-reactor:
  rag:
    enabled: true
    max-results: 5
    filesystem:
      enabled: true
      path: ./knowledge
      glob: "**/*.{txt,md}"
    jdbc:
      enabled: false
      table: knowledge_documents
      content-column: content
```

Инструмент `knowledge_search` регистрируется автоматически и доступен всем планировщикам.

---

## Динамические JavaScript-инструменты

Подключите `intent-reactor-tool-dynamic`, включите и регистрируйте скрипты через `ScriptRepository`:

```yaml
intent-reactor:
  tools:
    dynamic-scripting:
      enabled: true
      max-execution-time: PT5S
      script-repository: in-memory   # in-memory | jdbc
      allowed-classes:
        - java.lang.Math
        - java.util.List
```

```java
@Autowired ScriptRepository scriptRepository;

scriptRepository.save(new ScriptDefinition(
    "currency_convert",
    "Конвертирует сумму из одной валюты в другую по фиксированному курсу.",
    /* схема параметров */ Map.of(...),
    /* скрипт */ "var rate = params.from === 'USD' ? 90.0 : 1.0; rate * params.amount;"
));
```

---

## MCP-интеграция

### Потребление внешних MCP-серверов

Подключите `intent-reactor-mcp-client`:

```yaml
intent-reactor:
  mcp:
    client:
      enabled: true
      servers:
        - name: my-server
          transport: SSE
          url: http://localhost:8090
          prefix-tool-names: true
```

### Экспорт в виде MCP-сервера

Подключите `intent-reactor-mcp-server` вместе с `spring-ai-starter-mcp-server`. Все зарегистрированные бины `Tool` публикуются автоматически.

---

## Ключевые точки расширения

| Интерфейс | Назначение |
|---|---|
| `Tool` | Реализация вызываемого действия; пометить `@Component` |
| `SimulatableTool` | Инструмент с поддержкой dry-run симуляции (используется LATS) |
| `Planner` | Пользовательская стратегия планирования; объявить `@Primary` для замены дефолтной |
| `SessionStore` | Пользовательский бэкенд для хранения сессий |
| `IntentPreprocessor` | Пользовательская логика классификации намерений |
| `PromptContextProvider` | Внедрение дополнительных переменных в шаблоны промптов |
| `ToolProvider` | Пользовательское обнаружение и фильтрация инструментов для конкретной сессии |

---

## Справочник конфигурации

```yaml
intent-reactor:
  llm:
    provider: openai          # информационная метка
    model: gpt-4o
    temperature: 0.1
    prompt-resources:         # переопределение любого встроенного шаблона промпта
      system: classpath:prompts/default-system-ru.md
      intent: classpath:prompts/default-intent-ru.md

  planning:
    strategy: react
    autonomous: false         # true = пропускать подтверждение пользователя для рискованных инструментов
    max-steps: 10
    max-retries: 3
    confirmation-timeout: PT30M
    parallel-timeout: PT60S
    multi-intent:
      strategy: sequential    # sequential | parallel | llm-driven
    reflexion:
      max-reflection-steps: 3
    lats:
      max-iterations: 50
      exploration-constant: 1.4
      branching-factor: 3
    context-window:
      max-messages: 20
      max-message-chars: 8000
      max-snapshot-chars: 30000
      compression:
        enabled: false
        max-tokens: 4000
        trigger-ratio: 0.85

  session:
    store: in-memory          # in-memory | filesystem | jdbc | jpa
    filesystem:
      path: ./sessions
    jdbc:
      table-name: intent_reactor_sessions

  logging:
    enabled: true             # структурированное логирование событий через SLF4J
```

---

## Spring Events

Подпишитесь на события фреймворка через `@EventListener`:

```java
@EventListener
public void onPlanCompleted(PlanCompletedEvent event) {
    log.info("Сессия {} завершена: {}", event.getSessionId(), event.getFinalText());
}

@EventListener
public void onConfirmationRequired(ConfirmationRequiredEvent event) {
    // отправить в UI или очередь сообщений
}
```

Доступные события: `IntentAnalysisStartedEvent`, `IntentAnalysisCompletedEvent`, `PlanStartedEvent`, `PlanStepStartedEvent`, `PlanStepCompletedEvent`, `PlanCompletedEvent`, `PlanFailedEvent`, `ConfirmationRequiredEvent`, `ContextCompressedEvent`.

---

## Лицензия

Apache License 2.0
