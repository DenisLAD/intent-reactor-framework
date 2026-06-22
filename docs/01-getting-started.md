# Getting Started

IntentReactor is a Spring Boot starter library that adds LLM-powered intent analysis and autonomous action planning to any Spring Boot application. You define **tools** (callable actions), and the framework's ReACT loop figures out which tools to invoke, in what order, to satisfy a natural-language user request.

---

## 1. Add the dependency

Add the starter plus a Spring AI model provider to your `pom.xml`:

```xml
<!-- IntentReactor -->
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-spring-boot-starter</artifactId>
    <version>0.1.6</version>
</dependency>

<!-- Spring AI – OpenAI example; replace with your provider -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

Spring AI milestone/snapshot repositories are required (already declared in the parent POM):

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
    </repository>
</repositories>
```

---

## 2. Minimal configuration

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
    strategy: react      # default; see docs/strategies/ for alternatives
    max-steps: 10
    autonomous: false    # true = skip user confirmation for risky tools
```

That is all that is required to start. Every other property has a sensible default.

---

## 3. Implement your first tool

Any Spring bean implementing `Tool` is discovered automatically at startup. No registration needed.

```java
package com.example.demo;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class OrderLookupTool implements Tool {

    @Override
    public String getName() {
        // Unique identifier sent to the LLM. Use lowercase_underscore style.
        return "order_lookup";
    }

    @Override
    public String getDescription() {
        // This text appears in the LLM prompt. Be precise about inputs and outputs.
        return "Looks up an order by ID and returns its status and estimated delivery date.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        // Standard JSON Schema object describing accepted parameters.
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "orderId", Map.of(
                    "type", "string",
                    "description", "The order identifier, e.g. ORD-123"
                )
            ),
            "required", List.of("orderId")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String orderId = (String) input.getParameters().get("orderId");
        // ... real lookup logic here ...
        return ToolResult.ok(Map.of(
            "orderId", orderId,
            "status", "shipped",
            "eta", "2025-08-01"
        ));
    }

    @Override
    public boolean isRisky() {
        // false = execute without user confirmation
        // true  = pause and return AWAITING_CONFIRMATION (see docs/07-confirmation-flow.md)
        return false;
    }
}
```

---

## 4. Process your first request

Inject `IntentReactorService` and call `process()`:

```java
import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.ReactorResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final IntentReactorService reactor;

    public ChatController(IntentReactorService reactor) {
        this.reactor = reactor;
    }

    // ── One-shot (no session, state is discarded after the call) ──────────────
    @PostMapping("/oneshot")
    public String oneshot(@RequestBody String message) {
        ReactorResponse response = reactor.process(message, null);
        return response.getFinalText();
    }

    // ── Dialog mode (session preserved across calls) ───────────────────────────
    @PostMapping("/session/{sessionId}")
    public String chat(@PathVariable String sessionId, @RequestBody String message) {
        ReactorResponse response = reactor.process(sessionId, message);

        if (response.getStatus() == PlanStatus.COMPLETED) {
            return response.getFinalText();
        } else if (response.getStatus() == PlanStatus.AWAITING_CONFIRMATION) {
            // See docs/07-confirmation-flow.md for the full flow
            return "Confirmation required: " + response.getConfirmationRequest().getDescription();
        } else {
            return "Failed: " + response.getFinalText();
        }
    }
}
```

---

## 5. What happens when you send a message

1. `IntentPreprocessor` asks the LLM to classify the message into one or more intents.
2. `Planner` runs in a loop. On each iteration it asks the LLM: *"Given the current goal and history, what tool should I call next?"*
3. The selected tool's `execute()` method is invoked; the result is added to the session history.
4. The loop repeats until the planner decides it is `done` (returns `finalMessage`) or a `failed` state is reached, or `max-steps` is exhausted.
5. A `ReactorResponse` is returned with `status`, `finalText`, `actions` (all tool calls), and `reasoningSteps`.

---

## Next steps

| Topic | File |
|---|---|
| All extension interfaces | [02-core-concepts.md](02-core-concepts.md) |
| Full request lifecycle | [03-request-lifecycle.md](03-request-lifecycle.md) |
| Tool catalogue & advanced tool patterns | [04-tools.md](04-tools.md) |
| Persistent session stores | [05-session-stores.md](05-session-stores.md) |
| Planning strategies | [strategies/00-overview.md](strategies/00-overview.md) |
| Complete configuration reference | [13-configuration-reference.md](13-configuration-reference.md) |
