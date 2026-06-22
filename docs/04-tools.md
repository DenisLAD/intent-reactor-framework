# Tools

Tools are the actions the planner can invoke. Any Spring bean implementing `Tool` is discovered automatically; no registration is needed.

---

## The Tool interface

```java
public interface Tool {
    String getName();
    String getDescription();
    Map<String, Object> getParameterSchema();
    ToolResult execute(ToolInput input);
    boolean isRisky();
    default boolean isGenerator() { return false; }
}
```

### getName()

- Use `lowercase_underscore` style: `order_lookup`, `send_email`, `create_ticket`.
- The name is embedded verbatim in every LLM prompt. Short, descriptive names improve planner accuracy.
- Must be unique across all tools in the application context.

### getDescription()

- Write in imperative form: *"Looks up an order by ID and returns its status."*
- Mention what the tool **needs** (inputs) and what it **returns** (outputs).
- Do not exceed ~200 characters — the description is part of the token budget.

### getParameterSchema()

Returns a [JSON Schema](https://json-schema.org/) `object` describing the tool's parameters.

```java
@Override
public Map<String, Object> getParameterSchema() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "orderId", Map.of(
                "type", "string",
                "description", "Order identifier, e.g. ORD-123"
            ),
            "includeHistory", Map.of(
                "type", "boolean",
                "description", "Whether to include order history",
                "default", false
            )
        ),
        "required", List.of("orderId")
    );
}
```

### execute(ToolInput)

`ToolInput` carries:
- `getParameters()` — `Map<String, Object>` populated by the planner from the JSON Schema.
- `getSessionId()` — current session ID; use it to read session state via `SessionStore` if needed.

Always catch checked exceptions internally and return `ToolResult.error()`. The framework catches unchecked exceptions too, but logging is cleaner if you handle domain errors yourself.

```java
@Override
public ToolResult execute(ToolInput input) {
    try {
        String id = (String) input.getParameters().get("orderId");
        Order order = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Not found: " + id));
        return ToolResult.ok(Map.of("status", order.getStatus(), "eta", order.getEta()));
    } catch (Exception e) {
        return ToolResult.error(e.getMessage());
    }
}
```

### isRisky()

Return `true` for destructive or irreversible actions: sending emails, processing payments, deleting records. When `true` and `autonomous=false`, plan execution pauses and `AWAITING_CONFIRMATION` is returned. See [07-confirmation-flow.md](07-confirmation-flow.md).

### isGenerator()

Return `true` for tools that produce other tools at runtime (e.g., `DynamicScriptTool`). Generator tools are invisible to the LLM — they are not included in the tool list sent to the planner. `DefaultToolProvider` filters them out automatically.

---

## SimulatableTool

Extend `Tool` with a dry-run simulation used by the [LATS planner](strategies/03-lats.md) during Monte Carlo tree search.

```java
@Component
public class WeatherTool implements SimulatableTool {

    @Override
    public ToolResult execute(ToolInput input) {
        String city = (String) input.getParameters().get("city");
        return ToolResult.ok(fetchRealWeather(city)); // real HTTP call
    }

    @Override
    public ToolResult simulate(ToolInput input) {
        // Return plausible data without side effects
        return ToolResult.ok(Map.of("temperature", 22, "condition", "sunny"));
    }

    // ... getName(), getDescription(), getParameterSchema(), isRisky() ...
}
```

If `SimulatableTool` is not implemented, LATS falls back to real execution during simulation (controlled by `planning.lats.allow-real-actions-in-simulation`).

---

## Custom ToolProvider

`ToolProvider` controls which tools are visible per session. The default returns all non-generator beans.

```java
@Bean
@Primary
public ToolProvider featureFlagToolProvider(List<Tool> allTools) {
    return session -> {
        boolean betaUser = Boolean.TRUE.equals(session.getAttributes().get("betaUser"));
        return allTools.stream()
            .filter(t -> !t.isGenerator())
            .filter(t -> !t.getName().equals("beta_feature") || betaUser)
            .toList();
    };
}
```

---

## Tool Commons catalogue

The `intent-reactor-tool-commons` module provides ready-made tools. Add it to your `pom.xml` and declare individual tools as `@Bean` or enable via component scanning.

| Tool name | Description | Risky |
|---|---|---|
| `read_file` | Read file contents or list directory; `offset` + `limit` for paging | No |
| `write_file` | Create or overwrite a file (creates parent dirs) | **Yes** |
| `edit_file` | Replace `old_string` with `new_string`; `replace_all` flag | **Yes** |
| `glob` | Find files matching a glob pattern; returns up to 100 paths | No |
| `grep` | Search file contents with a regex; returns up to 100 matches | No |
| `calculator` | Evaluate arithmetic expressions (`+`, `-`, `*`, `/`, parentheses) | No |
| `weather` | Return current weather for a city (stub implementation) | No |
| `ask_user` | Pause and prompt the user with a question (`isRisky=true` triggers confirmation UI) | **Yes** |
| `datetime` | Return current date/time in a configurable format | No |
| `file_content_extractor` | Segment a file into numbered chunks by word count | No |
| `web_fetch` | Fetch a URL and return content as markdown, text, or HTML (5 MB limit) | No |
| `apply_patch` | Apply a unified diff patch to a file | **Yes** |
| `todo_write_tool` | Write a TODO list to a file | No |
| `markdown_file_scanner_tool` | Scan a directory and index all Markdown files | No |

### Key tool details

**`read_file`** params: `file_path` (required), `offset` (default 1), `limit` (default/max 2000 lines). Returns numbered lines; detects binary files.

**`edit_file`** params: `file_path`, `old_string`, `new_string`, `replace_all`. Multi-strategy matching: exact → line-trimmed → whitespace-normalized. Errors on multiple matches unless `replace_all=true`.

**`web_fetch`** params: `url` (http/https only), `format` (`markdown`|`text`|`html`, default `markdown`), `timeout` (max 120 s). Retries with different User-Agent on Cloudflare 403.

**`ask_user`** params: `question`, `answer` (auto-filled by UI). Marked risky so the confirmation UI shows a text-input field.

---

## Thread safety

The framework may call `execute()` concurrently from different sessions. Design tools to be stateless, or use thread-safe data structures for any shared state. Avoid instance-level mutable fields.
