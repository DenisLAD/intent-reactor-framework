# Dynamic JavaScript Tools

The `intent-reactor-tool-dynamic` module lets the LLM generate new tools at runtime as JavaScript snippets. Scripts are stored in a repository and executed inside a Rhino sandbox with configurable class restrictions and execution timeouts.

---

## Dependency

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-tool-dynamic</artifactId>
    <version>0.1.6</version>
</dependency>
```

Also requires Mozilla Rhino on the classpath:

```xml
<dependency>
    <groupId>org.mozilla</groupId>
    <artifactId>rhino</artifactId>
    <version>1.7.15</version>
</dependency>
```

---

## Enable and configure

```yaml
intent-reactor:
  tools:
    dynamic-scripting:
      enabled: true
      max-execution-time: PT5S         # script execution timeout (default)
      script-repository: in-memory     # in-memory | jdbc
      max-generation-retries: 3        # LLM retries on syntax errors
      allowed-classes:                 # extra Java classes accessible from scripts
        - java.lang.Math
        - java.util.ArrayList
```

---

## How it works

### DynamicScriptTool (generator)

`DynamicScriptTool` is a **generator tool** (`isGenerator() = true`) — it is invisible to the regular planner. Instead, `DynamicToolProvider` uses it to manage the script repository and wraps each active `ScriptDefinition` as a `ScriptToolWrapper` that is visible to the planner.

The planner can invoke `dynamic_script_tool` with one of three operations:

| Operation | Description |
|---|---|
| `create` | Ask the LLM to generate a new JavaScript tool |
| `adapt` | Modify an existing script for a new use case |
| `list` | List all active scripts in the repository |

```java
// The planner will invoke this automatically, but you can also call it manually
// by asking: "Create a tool that converts Celsius to Fahrenheit"
```

### Script generation

When `create` is invoked, the framework:
1. Sends the description and optional sample data to the LLM.
2. The LLM writes a JavaScript function following the required format.
3. The script is compiled and validated inside the sandbox before saving.
4. On syntax errors, the framework retries (up to `max-generation-retries` times) with error feedback.

---

## Script format

Scripts must be **ECMAScript 5.1** (Rhino limitation). No arrow functions, `let`/`const`, template literals, `for...of`, or destructuring.

```javascript
function execute(input) {
    // input is a JS object mirroring the tool's parameter schema
    var celsius = input.temperature;
    var fahrenheit = celsius * 9 / 5 + 32;
    return fahrenheit.toString() + "°F";
}
```

After the JavaScript code, append the JSON schema for the tool's parameters (used to build the LLM prompt):

```
SCHEMA: {
  "type": "object",
  "properties": {
    "temperature": { "type": "number", "description": "Temperature in Celsius" }
  },
  "required": ["temperature"]
}
```

The return value may be a string, number, or a JavaScript object (converted to `Map`). `undefined` and `null` are treated as empty results.

---

## Sandbox security

The Rhino sandbox enforces strict class restrictions.

### Always denied

- `java.io.*` — no filesystem access
- `java.net.*` — no network access
- `java.nio.*` — no NIO channels
- `sun.*`, `com.sun.*`, `jdk.*` — no internal JDK APIs
- `java.lang.reflect.*`, `java.lang.invoke.*` — no reflection
- `java.lang.System`, `java.lang.Runtime`, `java.lang.Thread`, `java.lang.ProcessBuilder`, `java.lang.Class`

### Always allowed

`java.lang.Math`, `String`, `Integer`, `Double`, `Boolean`, `Long`, `Number`, `java.util.Map`, `java.util.List`, `java.util.ArrayList`, `java.util.HashMap`, all Rhino JavaScript classes.

### Configurable whitelist

Add trusted classes via `intent-reactor.tools.dynamic-scripting.allowed-classes`.

---

## ScriptRepository

### In-memory (default)

Scripts are stored in a `ConcurrentHashMap`. Use for development and testing.

### JDBC

```yaml
intent-reactor:
  tools:
    dynamic-scripting:
      script-repository: jdbc
```

Required schema:

```sql
CREATE TABLE intent_reactor_scripts (
    id               VARCHAR(255) NOT NULL PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    version          VARCHAR(50)  NOT NULL,
    code             TEXT         NOT NULL,
    description      TEXT,
    parameter_schema TEXT,              -- JSON
    tags             TEXT,              -- JSON array
    status           VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    risky            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);

CREATE INDEX idx_scripts_name_status ON intent_reactor_scripts (name, status);
CREATE INDEX idx_scripts_status      ON intent_reactor_scripts (status);
```

---

## ScriptDefinition fields

| Field | Type | Description |
|---|---|---|
| `id` | String | UUID |
| `name` | String | Tool name (converted to `lowercase_underscore`) |
| `version` | String | Incremented on each `adapt` call (v1 → v2) |
| `description` | String | Shown in LLM prompt |
| `code` | String | JavaScript source |
| `parameterSchema` | Map | JSON Schema for parameters |
| `status` | ScriptStatus | `ACTIVE` or `ARCHIVED` |
| `risky` | boolean | Whether the tool requires confirmation |
| `tags` | List\<String\> | For filtering / `list` operation |

---

## Register a script manually

```java
@Autowired
ScriptRepository scriptRepository;

void registerTool() {
    ScriptDefinition def = new ScriptDefinition(
        UUID.randomUUID().toString(),          // id
        "celsius_to_fahrenheit",               // name
        "1",                                   // version
        "Converts a temperature from Celsius to Fahrenheit.",  // description
        "function execute(input) { return (input.celsius * 9/5 + 32) + '°F'; }",
        Map.of(                                // parameterSchema
            "type", "object",
            "properties", Map.of(
                "celsius", Map.of("type", "number", "description", "Temperature in Celsius")
            ),
            "required", List.of("celsius")
        )
    );
    scriptRepository.save(def);
}
```

After saving, call `DynamicToolProvider.invalidateCache()` to make the new tool available immediately (the cache is also invalidated automatically on the next request if `InvalidationAwareScriptRepository` detects a change).
