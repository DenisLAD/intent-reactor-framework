# Session Stores

`SessionState` holds the complete mutable state of a conversation — message history, plan progress, and arbitrary attributes. A `SessionStore` persists it between calls.

---

## SessionState structure

```java
SessionState {
    String id;                       // unique session identifier
    List<Message> messages;          // full dialog history (read-only view)
    PlanState planState;             // goal, status, completed steps
    Map<String, Object> attributes;  // free-form cross-cutting data
    LocalDateTime createdAt;
    LocalDateTime updatedAt;         // updated by touch() on every mutation
}
```

### Message roles

| Role | Added by | Sent to LLM as |
|---|---|---|
| `USER` | `process(sessionId, message)` | `UserMessage` |
| `ASSISTANT` | Planner's `DONE` step | `AssistantMessage` |
| `SYSTEM` | Tool results, OBSERVE/REFLECT steps | `UserMessage` (most LLMs expect observations in user turn) |

### Pinned messages

`Message.pinnedUser(content)` creates a pinned USER message that is never evicted from the sliding context window and excluded from LLM compression. The framework pins:
- The **first** message of every session.
- Any message sent when `SessionAttributeKeys.PIN_NEXT_USER_MESSAGE` (`"_pinNextUserMessage"`) is set to `true` in session attributes before calling `process()`.

Use pinning to preserve the original goal when a session is very long:

```java
session.getAttributes().put(SessionAttributeKeys.PIN_NEXT_USER_MESSAGE, true);
reactor.process(sessionId, "Actually, ignore that. Focus on task X instead.");
```

### Internal attribute keys

The framework writes the following keys into `session.attributes`:

| Key | Type | Description |
|---|---|---|
| `"originalIntent"` | `IntentAnalysisResult` | Cached intent from `analyze()`; preserved across planning iterations |
| `"pendingStep"` | `PlanStep` (serialized as Map) | Step paused awaiting confirmation |
| `"confirmationRequestedAt"` | `String` (LocalDateTime) | When confirmation was requested; used for timeout check |
| `"pendingModifiedParameters"` | `Map<String, Object>` | User-modified parameters from `ConfirmationResult` |
| `"multiIntentState"` | `MultiIntentContext` | Orchestration state during multi-intent processing |
| `"searchTree"` | `SearchTree` | LATS MCTS tree; persisted across planning iterations |
| `"thoughts"` | `List<String>` | REASON step contents (not written to message history) |

Application code may store arbitrary Jackson-serializable values here to share context between tool calls within the same session.

---

## Choosing a store

| Store | Config value | Survives restart | Use case |
|---|---|---|---|
| In-memory | `in-memory` (default) | No | Development, tests, single-instance stateless |
| Filesystem | `filesystem` | Yes | Simple persistence without a database |
| JDBC | `jdbc` | Yes | Relational database; production-ready |
| JPA | `jpa` | Yes | Projects already using Spring Data JPA |

---

## In-memory (default)

No configuration needed. Sessions are stored in a `ConcurrentHashMap` and lost on restart.

```yaml
intent-reactor:
  session:
    store: in-memory
```

---

## Filesystem

Sessions are serialized as JSON files, one per session ID, in a configurable directory. Writes are atomic (temp file + move).

```yaml
intent-reactor:
  session:
    store: filesystem
    filesystem:
      path: ./sessions   # relative to the working directory
```

File names follow the pattern `{sessionId}.json`. Concurrent writes to the **same** session are serialized with a per-session lock.

---

## JDBC

Add the module:

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-session-jdbc</artifactId>
    <version>0.1.6</version>
</dependency>
```

Configure:

```yaml
intent-reactor:
  session:
    store: jdbc
    jdbc:
      table-name: intent_reactor_sessions   # default
```

Create the table before starting the application:

```sql
CREATE TABLE intent_reactor_sessions (
    id         VARCHAR(255) NOT NULL PRIMARY KEY,
    state      TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
```

The `state` column stores the full `SessionState` as a JSON string. The store uses an upsert pattern: UPDATE first, INSERT if zero rows affected.

---

## JPA

Add the module:

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-session-jpa</artifactId>
    <version>0.1.6</version>
</dependency>
```

Configure:

```yaml
intent-reactor:
  session:
    store: jpa

spring:
  jpa:
    hibernate:
      ddl-auto: update   # or manage schema manually
```

The JPA entity (`SessionEntity`) maps to the same `intent_reactor_sessions` table. Fields:

| Column | Type | Description |
|---|---|---|
| `id` | VARCHAR (PK) | Session identifier |
| `state` | TEXT | JSON-serialized `SessionState` |
| `created_at` | TIMESTAMP | |
| `updated_at` | TIMESTAMP | |

---

## Custom SessionStore

Implement the interface and declare a `@Bean`:

```java
@Bean
@Primary
public SessionStore redisSessionStore(RedisTemplate<String, String> redis,
                                      ObjectMapper mapper) {
    return new RedisSessionStore(redis, mapper);
}
```

The bean takes precedence over all auto-configured stores because of `@ConditionalOnMissingBean(SessionStore.class)`.
