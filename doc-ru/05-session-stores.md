# Хранилища сессий

## Структура SessionState

```java
public class SessionState {
    String id;                        // идентификатор сессии
    List<Message> messages;           // история диалога
    PlanState planState;              // текущее состояние планирования
    Map<String, Object> attributes;  // произвольные атрибуты сессии
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

---

## Роли сообщений

| Роль | Кто создаёт | Назначение |
|---|---|---|
| `USER` | Входящее сообщение от `process()` | Запрос пользователя |
| `ASSISTANT` | Ответ LLM | Рассуждение и план следующего шага |
| `SYSTEM` | Фреймворк | Результат инструмента, системный контекст |

### Закреплённые сообщения

```java
message.setPinned(true);
```

Закреплённые сообщения не вытесняются скользящим окном контекста. Они переставляются на своё исходное хронологическое место в истории, даже если выходят за границу окна.

Фреймворк автоматически закрепляет:
- Первое сообщение каждой сессии.
- Сообщения после возобновления из состояния ожидания подтверждения.

### Ручное закрепление

```java
session.getAttributes().put(SessionAttributeKeys.PIN_NEXT_USER_MESSAGE, true);
// Следующий вызов process() создаст закреплённое сообщение
```

---

## Системные атрибуты сессии

Фреймворк использует следующие зарезервированные ключи атрибутов:

| Ключ | Тип | Назначение |
|---|---|---|
| `PIN_NEXT_USER_MESSAGE` | Boolean | Закрепить следующее входящее сообщение |
| `pendingStep` | PlanStep | Ожидающий шаг при AWAITING_CONFIRMATION |
| `originalIntent` | String | Исходная цель для стратегии Reflexion |
| `reflections` | List\<String\> | Накопленные рефлексии |
| `reasoningSteps` | List\<ReasoningStep\> | Шаги REASON текущего планирования |
| `multiIntentContext` | MultiIntentContext | Метаданные параллельного выполнения |

---

## Сравнение хранилищ

| Хранилище | Постоянность | Масштабируемость | Когда использовать |
|---|---|---|---|
| `in-memory` | Нет (перезапуск = потеря данных) | Одна JVM | Разработка, тесты, прототипы |
| `filesystem` | Да | Одна нода | Простые продакшн-деплои на одной ноде |
| `jdbc` | Да | Любое | Продакшн с несколькими нодами |
| `jpa` | Да | Любое | Если уже используется Spring Data JPA |

---

## In-Memory (по умолчанию)

```yaml
intent-reactor:
  session:
    store: in-memory
```

Хранит сессии в `ConcurrentHashMap`. Не требует дополнительной настройки.

---

## Filesystem

```yaml
intent-reactor:
  session:
    store: filesystem
    filesystem:
      path: ./sessions   # директория для JSON-файлов сессий
```

Каждая сессия сохраняется как отдельный JSON-файл: `<path>/<sessionId>.json`. Запись атомарная (через временный файл с последующим переименованием).

---

## JDBC

```yaml
intent-reactor:
  session:
    store: jdbc
    jdbc:
      table-name: intent_reactor_sessions
```

Требуемая схема таблицы:

```sql
CREATE TABLE intent_reactor_sessions (
    id          VARCHAR(255)  NOT NULL PRIMARY KEY,
    data        TEXT          NOT NULL,  -- JSON-сериализованный SessionState
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);

CREATE INDEX idx_sessions_updated ON intent_reactor_sessions (updated_at);
```

Операции используют upsert-паттерн (`INSERT ... ON CONFLICT DO UPDATE` для PostgreSQL / `MERGE` для других диалектов).

---

## JPA

```yaml
intent-reactor:
  session:
    store: jpa
```

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-session-jpa</artifactId>
    <version>0.1.6</version>
</dependency>
```

`SessionEntity` поля:

| Поле | Тип JPA | Описание |
|---|---|---|
| `id` | `@Id String` | Идентификатор сессии |
| `data` | `@Lob String` | JSON-сериализованный SessionState |
| `createdAt` | `LocalDateTime` | |
| `updatedAt` | `LocalDateTime` | |

---

## Кастомное хранилище

```java
@Component
@Primary
public class RedisSessionStore implements SessionStore {

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    @Override
    public SessionState load(String sessionId) {
        String json = redis.opsForValue().get("session:" + sessionId);
        if (json == null) return new SessionState(sessionId);
        return objectMapper.readValue(json, SessionState.class);
    }

    @Override
    public void save(SessionState session) {
        String json = objectMapper.writeValueAsString(session);
        redis.opsForValue().set("session:" + session.getId(), json,
            Duration.ofHours(24));
    }

    @Override
    public void delete(String sessionId) {
        redis.delete("session:" + sessionId);
    }
}
```
