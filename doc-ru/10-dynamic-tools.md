# Динамические JavaScript-инструменты

Модуль `intent-reactor-tool-dynamic` позволяет LLM генерировать новые инструменты во время выполнения в виде JavaScript-фрагментов. Скрипты хранятся в репозитории и выполняются внутри Rhino-сэндбокса с настраиваемыми ограничениями классов и таймаутом выполнения.

---

## Зависимость

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-tool-dynamic</artifactId>
    <version>0.1.6</version>
</dependency>

<!-- Mozilla Rhino (движок JavaScript) -->
<dependency>
    <groupId>org.mozilla</groupId>
    <artifactId>rhino</artifactId>
    <version>1.7.15</version>
</dependency>
```

---

## Включение и настройка

```yaml
intent-reactor:
  tools:
    dynamic-scripting:
      enabled: true
      max-execution-time: PT5S         # таймаут выполнения скрипта
      script-repository: in-memory     # in-memory | jdbc
      max-generation-retries: 3        # повторы LLM при синтаксических ошибках
      allowed-classes:                 # дополнительные Java-классы в скриптах
        - java.lang.Math
        - java.util.ArrayList
```

---

## Принцип работы

### DynamicScriptTool (генератор)

`DynamicScriptTool` — **инструмент-генератор** (`isGenerator() = true`), невидимый для обычного планировщика. `DynamicToolProvider` использует его для управления репозиторием скриптов и оборачивает каждый активный `ScriptDefinition` как `ScriptToolWrapper`, который виден планировщику.

Планировщик может вызвать `dynamic_script_tool` с одной из трёх операций:

| Операция | Описание |
|---|---|
| `create` | Попросить LLM сгенерировать новый JavaScript-инструмент |
| `adapt` | Модифицировать существующий скрипт для нового сценария |
| `list` | Вывести список всех активных скриптов в репозитории |

### Генерация скрипта

При вызове `create` фреймворк:
1. Отправляет описание и опциональные примерные данные LLM.
2. LLM пишет JavaScript-функцию в требуемом формате.
3. Скрипт компилируется и валидируется внутри сэндбокса перед сохранением.
4. При синтаксических ошибках — повтор (до `max-generation-retries` раз) с передачей ошибки обратно.

---

## Формат скрипта

Скрипты должны быть на **ECMAScript 5.1** (ограничение Rhino). Не поддерживаются: стрелочные функции, `let`/`const`, шаблонные литералы, `for...of`, деструктуризация.

```javascript
function execute(input) {
    // input — JS-объект, соответствующий JSON Schema инструмента
    var celsius = input.temperature;
    var fahrenheit = celsius * 9 / 5 + 32;
    return fahrenheit.toString() + "°F";
}
```

После кода JavaScript добавьте JSON Schema параметров инструмента:

```
SCHEMA: {
  "type": "object",
  "properties": {
    "temperature": { "type": "number", "description": "Температура в Цельсиях" }
  },
  "required": ["temperature"]
}
```

Возвращаемое значение может быть строкой, числом или JavaScript-объектом (конвертируется в `Map`). `undefined` и `null` трактуются как пустой результат.

---

## Безопасность сэндбокса

### Всегда запрещено

- `java.io.*` — нет доступа к файловой системе
- `java.net.*` — нет сетевого доступа
- `java.nio.*` — нет NIO-каналов
- `sun.*`, `com.sun.*`, `jdk.*` — нет внутренних API JDK
- `java.lang.reflect.*`, `java.lang.invoke.*` — нет рефлексии
- `java.lang.System`, `java.lang.Runtime`, `java.lang.Thread`, `java.lang.ProcessBuilder`, `java.lang.Class`

### Всегда разрешено

`java.lang.Math`, `String`, `Integer`, `Double`, `Boolean`, `Long`, `Number`, `java.util.Map`, `java.util.List`, `java.util.ArrayList`, `java.util.HashMap`, все классы Rhino JavaScript.

### Настраиваемый белый список

Добавьте доверенные классы через `intent-reactor.tools.dynamic-scripting.allowed-classes`.

---

## ScriptRepository

### In-memory (по умолчанию)

Скрипты хранятся в `ConcurrentHashMap`. Используйте для разработки и тестирования.

### JDBC

```yaml
intent-reactor:
  tools:
    dynamic-scripting:
      script-repository: jdbc
```

Схема таблицы:

```sql
CREATE TABLE intent_reactor_scripts (
    id               VARCHAR(255) NOT NULL PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    version          VARCHAR(50)  NOT NULL,
    code             TEXT         NOT NULL,
    description      TEXT,
    parameter_schema TEXT,
    tags             TEXT,
    status           VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    risky            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);

CREATE INDEX idx_scripts_name_status ON intent_reactor_scripts (name, status);
CREATE INDEX idx_scripts_status      ON intent_reactor_scripts (status);
```

---

## Поля ScriptDefinition

| Поле | Тип | Описание |
|---|---|---|
| `id` | String | UUID |
| `name` | String | Имя инструмента (конвертируется в `snake_case`) |
| `version` | String | Увеличивается при каждом `adapt` (v1 → v2) |
| `description` | String | Отображается в промпте LLM |
| `code` | String | Исходный код JavaScript |
| `parameterSchema` | Map | JSON Schema параметров |
| `status` | ScriptStatus | `ACTIVE` или `ARCHIVED` |
| `risky` | boolean | Требует ли инструмент подтверждения |
| `tags` | List\<String\> | Для фильтрации / операции `list` |

---

## Ручная регистрация скрипта

```java
@Autowired
ScriptRepository scriptRepository;

void registerTool() {
    ScriptDefinition def = new ScriptDefinition(
        UUID.randomUUID().toString(),
        "celsius_to_fahrenheit",
        "1",
        "Конвертирует температуру из Цельсия в Фаренгейт.",
        "function execute(input) { return (input.celsius * 9/5 + 32) + '°F'; }",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "celsius", Map.of("type", "number", "description", "Температура в Цельсиях")
            ),
            "required", List.of("celsius")
        )
    );
    scriptRepository.save(def);
}
```

После сохранения вызовите `DynamicToolProvider.invalidateCache()` для немедленного появления инструмента (кэш также инвалидируется автоматически при следующем запросе, если `InvalidationAwareScriptRepository` обнаружит изменения).
