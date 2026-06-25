# Интеграция с MCP

IntentReactor поддерживает [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) в двух направлениях:
- **Клиент** — подключение инструментов с удалённых MCP-серверов как локальных бинов `Tool`.
- **Сервер** — публикация инструментов и планировщиков IntentReactor как MCP-сервера.

---

## MCP-клиент

### Зависимость

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-mcp-client</artifactId>
    <version>0.1.6</version>
</dependency>
```

### Конфигурация

```yaml
intent-reactor:
  mcp:
    client:
      enabled: true
      treat-mcp-tools-as-risky: false     # пометить все MCP-инструменты как рискованные
      risky-tool-names:                    # конкретные инструменты — всегда рискованные
        - mcp_server_delete_file
      servers:
        - name: my-server
          transport: SSE
          url: http://localhost:8090
          sse-path: /sse
          connect-timeout: PT10S
          read-timeout: PT60S
          prefix-tool-names: true          # добавлять имя сервера к имени инструмента
```

### Типы транспорта

**SSE (Server-Sent Events)** — HTTP-транспорт для удалённых серверов:

```yaml
servers:
  - name: remote-tools
    transport: SSE
    url: https://tools.example.com
    sse-path: /mcp/sse
    connect-timeout: PT10S
    read-timeout: PT120S
```

**STDIO** — процессный транспорт для локальных CLI-инструментов:

```yaml
servers:
  - name: local-cli
    transport: STDIO
    command: /usr/local/bin/my-tool-server
    args:
      - --config
      - /etc/tool-config.json
    env:
      API_KEY: ${MY_TOOL_API_KEY}
```

### Именование инструментов

При `prefix-tool-names: true` (по умолчанию) имя инструмента получает префикс из имени сервера:
- Сервер `my-server` публикует инструмент `read_file` → доступен как `my_server_read_file`.

Переопределить префикс через `tool-name-prefix`:
```yaml
servers:
  - name: my-server
    tool-name-prefix: ext   # → ext_read_file
```

При `prefix-tool-names: false` используются исходные имена инструментов (риск коллизий с другими инструментами).

### Как интегрируются инструменты

`McpClientToolProvider` реализует `ToolProvider` и объединяет статически зарегистрированные бины `Tool` с динамически обнаруженными MCP-инструментами. Список кешируется и инвалидируется при `McpToolsChangedEvent` (публикуется Spring AI при изменении списка инструментов на MCP-сервере).

### Несколько серверов

```yaml
intent-reactor:
  mcp:
    client:
      enabled: true
      servers:
        - name: filesystem-server
          transport: STDIO
          command: npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
        - name: search-server
          transport: SSE
          url: http://search-service:8080
```

---

## MCP-сервер

Публикация собственных инструментов IntentReactor для внешних MCP-клиентов (например, Claude Desktop, других агентов).

### Зависимости

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-mcp-server</artifactId>
    <version>0.1.6</version>
</dependency>

<!-- Инфраструктура MCP-сервера Spring AI -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
```

### Конфигурация

```yaml
intent-reactor:
  mcp:
    server:
      enabled: true
      expose-tools: true       # публиковать все бины Tool как MCP-инструменты
      expose-planner: false    # публиковать IntentReactorService как MCP-инструменты
      server-name: intent-reactor
      server-version: 1.0.0
```

### Что публикуется

**`expose-tools: true`** — все зарегистрированные бины `Tool` (включая MCP-клиентские инструменты, если модуль клиента тоже подключён) публикуются как `SyncToolSpecification` через стандартную инфраструктуру MCP-сервера Spring AI.

**`expose-planner: true`** — дополнительно регистрируются три MCP-инструмента, оборачивающие `IntentReactorService`:

| MCP-инструмент | Маппинг |
|---|---|
| `intent_reactor_process` | `IntentReactorService.process(sessionId, message)` |
| `proceed` | `IntentReactorService.proceedAfterConfirmation(sessionId, confirmation)` |
| `session` | `IntentReactorService.getSessionState(sessionId)` |

Это позволяет внешнему MCP-клиенту запускать полные пайплайны обработки намерений через IntentReactor.

### Доступ к MCP-эндпоинту

При наличии `spring-ai-starter-mcp-server` в classpath SSE-эндпоинт доступен по адресу:
```
http://localhost:{server.port}/sse
```

Укажите этот URL в любом MCP-клиенте для доступа ко всем опубликованным инструментам.
