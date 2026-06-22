# MCP Integration

IntentReactor supports the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) in two directions:
- **Client** — consume tools from remote MCP servers as local `Tool` beans.
- **Server** — expose IntentReactor's tools and planners as an MCP server.

---

## MCP Client

### Dependency

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-mcp-client</artifactId>
    <version>0.1.6</version>
</dependency>
```

### Configuration

```yaml
intent-reactor:
  mcp:
    client:
      enabled: true
      treat-mcp-tools-as-risky: false     # mark all MCP tools as risky
      risky-tool-names:                    # specific tools always marked risky
        - mcp_server_delete_file
      servers:
        - name: my-server
          transport: SSE
          url: http://localhost:8090
          sse-path: /sse                  # default
          connect-timeout: PT10S          # default
          read-timeout: PT60S             # default
          prefix-tool-names: true         # prepend server name to tool names
          # tool-name-prefix: my_prefix   # override prefix (null = use server name)
```

### Transport types

**SSE (Server-Sent Events)** — HTTP-based, suitable for remote servers:

```yaml
servers:
  - name: remote-tools
    transport: SSE
    url: https://tools.example.com
    sse-path: /mcp/sse
    connect-timeout: PT10S
    read-timeout: PT120S
```

**STDIO** — process-based, suitable for local CLI tools:

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

### Tool naming

When `prefix-tool-names: true` (default), tool names are prefixed with the server name:
- Server `my-server` exposes tool `read_file` → available as `my_server_read_file`.

Override the prefix with `tool-name-prefix`:
```yaml
servers:
  - name: my-server
    tool-name-prefix: ext   # → ext_read_file
```

Set `prefix-tool-names: false` to use bare tool names (risk of collision with other tools).

### How tools are integrated

`McpClientToolProvider` implements `ToolProvider` and merges statically-registered `Tool` beans with dynamically-discovered MCP tools. The tool list is cached and invalidated on `McpToolsChangedEvent` (published by Spring AI when the MCP server's tool list changes).

### Multiple servers

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

## MCP Server

Expose IntentReactor's own tools to external MCP clients (e.g., Claude Desktop, other agents).

### Dependencies

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-mcp-server</artifactId>
    <version>0.1.6</version>
</dependency>

<!-- Spring AI MCP server infrastructure -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
```

### Configuration

```yaml
intent-reactor:
  mcp:
    server:
      enabled: true            # default
      expose-tools: true       # publish all Tool beans as MCP tools (default)
      expose-planner: false    # publish IntentReactorService as planning tools
      server-name: intent-reactor
      server-version: 1.0.0
```

### What is exposed

**`expose-tools: true`** — all registered `Tool` beans (including MCP client tools if the client module is also present) are published as `SyncToolSpecification` entries via Spring AI's standard MCP server infrastructure.

**`expose-planner: true`** — three additional MCP tools are registered that wrap `IntentReactorService`:

| MCP Tool | Maps to |
|---|---|
| `intent_reactor_process` | `IntentReactorService.process(sessionId, message)` |
| `proceed` | `IntentReactorService.proceedAfterConfirmation(sessionId, confirmation)` |
| `session` | `IntentReactorService.getSessionState(sessionId)` |

This allows an external MCP client to run full intent-processing pipelines through IntentReactor.

### Accessing the MCP endpoint

With `spring-ai-starter-mcp-server` on the classpath, the SSE endpoint is available at:
```
http://localhost:{server.port}/sse
```

Point any MCP client at this URL to access all exposed tools.
