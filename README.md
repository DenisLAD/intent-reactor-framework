# IntentReactor

**IntentReactor** is a Spring Boot starter library that brings LLM-powered intent analysis and autonomous action planning to any Spring Boot application. It implements the [ReACT](https://arxiv.org/abs/2210.03629) (Reason + Act) paradigm and ships 14 interchangeable planning strategies — from vanilla chain-of-thought to Monte Carlo tree search — all configured with a single property.

---

## Features

- **ReACT planning loop** — iterative Thought → Action → Observation cycle driven by any Spring AI `ChatClient`
- **14 built-in planning strategies** — ReACT, Reflexion, LATS (MCTS), CoT, Zero-Shot CoT, Step-Back, Tree-of-Thoughts, Graph-of-Thoughts, STORM, Self-Ask, Least-to-Most, Plan-and-Solve, Reflection, Self-Discover
- **Multi-intent dispatch** — automatically decomposes compound user requests into sequential, parallel, or LLM-ordered sub-plans
- **Tool confirmation flow** — risky tools pause execution and return `AWAITING_CONFIRMATION`; resumed via `proceedAfterConfirmation()`
- **Pluggable session stores** — in-memory, filesystem, JDBC, and JPA implementations out of the box
- **RAG integration** — `knowledge_search` tool backed by in-memory, filesystem, JDBC, or vector sources
- **Dynamic JavaScript tools** — define tools at runtime as JavaScript snippets running in a Rhino sandbox with class-level restrictions and execution timeouts
- **MCP support** — consume remote MCP servers as local tools (SSE and STDIO transports); expose IntentReactor tools and planners as an MCP server
- **Context window management** — sliding message window, snapshot deduplication, per-message character limits, and optional LLM-based context compression
- **Spring Events** — `PlanStartedEvent`, `PlanStepCompletedEvent`, `PlanFailedEvent`, and more
- **Micrometer metrics** — transparent decorator wraps any planner and records step counts and latency

---

## Requirements

| Dependency | Version |
|---|---|
| Java | 17+ |
| Spring Boot | 3.5+ |
| Spring AI | 1.1+ |

---

## Quick Start

### 1. Add the starter

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Add a Spring AI provider (e.g., OpenAI):

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

### 2. Configure

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
    autonomous: false  # true = skip confirmation for risky tools
```

### 3. Implement a tool

Any Spring bean implementing `Tool` is automatically discovered:

```java
@Component
public class OrderLookupTool implements Tool {

    @Override
    public String getName() { return "order_lookup"; }

    @Override
    public String getDescription() {
        return "Looks up an order by ID and returns its status and estimated delivery date.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "orderId", Map.of("type", "string", "description", "The order identifier")
            ),
            "required", List.of("orderId")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String id = (String) input.getParameters().get("orderId");
        // ... lookup logic ...
        return ToolResult.ok(Map.of("status", "shipped", "eta", "2025-07-01"));
    }

    @Override
    public boolean isRisky() { return false; }
}
```

### 4. Process user messages

```java
@Autowired
IntentReactorService reactor;

// One-shot (no session)
ReactorResponse r = reactor.process("What is the status of order ORD-123?", null);
System.out.println(r.getFinalText());

// Dialog mode (persistent session)
ReactorResponse r1 = reactor.process("session-42", "Look up order ORD-123");
ReactorResponse r2 = reactor.process("session-42", "Now cancel it");

// Handle confirmation for risky tools
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

## Module Overview

```
intent-reactor-api                  Pure interfaces and DTOs (no Spring dependency)
intent-reactor-core                 Default implementations + Spring AutoConfiguration
intent-reactor-spring-boot-starter  Thin entry-point starter

Optional add-ons:
├── intent-reactor-session-jdbc     JDBC-backed SessionStore
├── intent-reactor-session-jpa      JPA-backed SessionStore
├── intent-reactor-tool-commons     Ready-made tools (calculator, web fetch, file I/O, …)
├── intent-reactor-tool-dynamic     Runtime JavaScript tools via Rhino sandbox
├── intent-reactor-rag              RAG KnowledgeSource + knowledge_search tool
├── intent-reactor-mcp-client       Consume remote MCP servers as local tools
├── intent-reactor-mcp-server       Expose IntentReactor as an MCP server
└── intent-reactor-strategies       14 extra planning strategies (CoT, ToT, GoT, STORM, …)
```

---

## Planning Strategies

Select a strategy with `intent-reactor.planning.strategy`:

| Strategy | Value | Description |
|---|---|---|
| ReACT *(default)* | `react` | Iterative Reason → Act → Observe loop |
| Reflexion | `reflexion` | Adds verbal self-reflection after tool failures |
| LATS | `lats` | Monte Carlo Tree Search over possible action paths |
| Chain-of-Thought | `cot` | Prepends a reasoning chain before acting |
| Zero-Shot CoT | `zero-shot-cot` | Adds "Let's think step by step" to the prompt |
| Step-Back | `step-back` | Abstracts to a higher-level question before solving |
| Reflection | `reflection` | Iterative critique-and-revision of the plan |
| Self-Ask | `self-ask` | Decomposes into sub-questions and answers them |
| Least-to-Most | `least-to-most` | Solves easiest sub-problems first |
| Plan-and-Solve | `plan-and-solve` | Explicit planning phase before execution |
| Tree of Thoughts | `tot` | Explores branching reasoning paths |
| Graph of Thoughts | `got` | Merges and scores thought nodes in a graph |
| Self-Discover | `self-discover` | Selects and adapts reasoning modules at runtime |
| STORM | `storm` | Multi-perspective research and synthesis |

---

## Session Stores

| Value | Description |
|---|---|
| `in-memory` *(default)* | Lost on restart; ideal for development |
| `filesystem` | Serialized to JSON files under a configurable directory |
| `jdbc` | Single table `intent_reactor_sessions`; add `intent-reactor-session-jdbc` |
| `jpa` | JPA entity; add `intent-reactor-session-jpa` |

```yaml
intent-reactor:
  session:
    store: jdbc
    jdbc:
      table-name: intent_reactor_sessions
```

---

## RAG Module

Add `intent-reactor-rag` and configure at least one knowledge source:

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

The `knowledge_search` tool is automatically registered and available to all planners.

---

## Dynamic JavaScript Tools

Add `intent-reactor-tool-dynamic`, enable it, and register scripts via `ScriptRepository`:

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
    "Converts amount from one currency to another using a fixed rate.",
    /* parameter schema */ Map.of(...),
    /* script */ "var rate = params.from === 'USD' ? 90.0 : 1.0; rate * params.amount;"
));
```

---

## MCP Integration

### Consume external MCP servers

Add `intent-reactor-mcp-client`:

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

### Expose as an MCP server

Add `intent-reactor-mcp-server` together with `spring-ai-starter-mcp-server`. All registered `Tool` beans are published automatically.

---

## Key Extension Points

| Interface | Purpose |
|---|---|
| `Tool` | Define a callable action; annotate with `@Component` |
| `SimulatableTool` | Tool that supports dry-run simulation (used by LATS) |
| `Planner` | Custom planning strategy; declare `@Primary` to override the default |
| `SessionStore` | Custom session persistence backend |
| `IntentPreprocessor` | Custom intent classification logic |
| `PromptContextProvider` | Inject extra template variables into system prompts |
| `ToolProvider` | Custom tool discovery and filtering per session |

---

## Configuration Reference

```yaml
intent-reactor:
  llm:
    provider: openai          # informational label
    model: gpt-4o
    temperature: 0.1
    prompt-resources:         # override any built-in prompt template
      system: classpath:prompts/default-system-ru.md
      intent: classpath:prompts/default-intent-ru.md

  planning:
    strategy: react
    autonomous: false         # true = skip user confirmation for risky tools
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
    enabled: true             # structured event logging via SLF4J
```

---

## Spring Events

Listen to framework events with `@EventListener`:

```java
@EventListener
public void onPlanCompleted(PlanCompletedEvent event) {
    log.info("Session {} completed: {}", event.getSessionId(), event.getFinalText());
}

@EventListener
public void onConfirmationRequired(ConfirmationRequiredEvent event) {
    // push to UI / message queue
}
```

Available events: `IntentAnalysisStartedEvent`, `IntentAnalysisCompletedEvent`, `PlanStartedEvent`, `PlanStepStartedEvent`, `PlanStepCompletedEvent`, `PlanCompletedEvent`, `PlanFailedEvent`, `ConfirmationRequiredEvent`, `ContextCompressedEvent`.

---

## License

Apache License 2.0
