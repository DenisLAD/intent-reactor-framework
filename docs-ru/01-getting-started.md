# Начало работы

## Что такое IntentReactor

IntentReactor — это Spring Boot Starter, реализующий паттерн ReACT (Reason + Act) для создания LLM-агентов. Он принимает пользовательские сообщения, классифицирует их намерения, а затем в итерационном цикле рассуждает, вызывает инструменты и наблюдает за результатами до получения окончательного ответа.

---

## Зависимость Maven

```xml
<!-- Стартер IntentReactor -->
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-spring-boot-starter</artifactId>
    <version>0.1.6</version>
</dependency>

<!-- Провайдер Spring AI (пример для OpenAI) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

Репозитории Spring AI milestone/snapshot уже настроены в родительском `pom.xml` фреймворка и доступны транзитивно.

---

## Минимальная конфигурация

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
    strategy: react
    max-steps: 20
```

---

## Первый инструмент

Все инструменты — это Spring-бины, реализующие интерфейс `Tool`. `DefaultToolProvider` обнаруживает их автоматически.

```java
@Component
public class GreetingTool implements Tool {

    @Override
    public String getName() {
        return "greet_user";   // snake_case, уникальное имя
    }

    @Override
    public String getDescription() {
        // Это описание видит LLM — делайте его понятным
        return "Приветствует пользователя по имени. Используйте, когда известно имя пользователя.";
    }

    @Override
    public String getSchema() {
        // JSON Schema для параметров
        return """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string", "description": "Имя пользователя" }
              },
              "required": ["name"]
            }
            """;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String name = (String) input.getParameters().get("name");
        return ToolResult.ok("Привет, " + name + "!");
    }

    @Override
    public boolean isRisky() {
        return false;   // true = требует подтверждения пользователя
    }

    @Override
    public boolean isGenerator() {
        return false;   // true = фабрика инструментов, невидима для LLM
    }
}
```

---

## Первый вызов

```java
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final IntentReactorService reactor;

    @PostMapping("/chat")
    public String chat(@RequestParam String sessionId,
                       @RequestParam String message) {
        return reactor.process(sessionId, message);
    }
}
```

`sessionId` идентифицирует диалог. Одна и та же строка сессии в разных вызовах — это продолжение разговора. Новая строка — новый диалог.

---

## Что происходит при вызове process()

1. Сессия загружается или создаётся.
2. Сообщение добавляется в историю и анализируется на намерения.
3. Планировщик ReACT запускается в цикле: рассуждает → вызывает инструменты → наблюдает результаты.
4. Когда планировщик выдаёт шаг `DONE`, цикл завершается и ответ возвращается.

---

## Следующие шаги

| Тема | Документ |
|---|---|
| Все интерфейсы расширения | [Основные концепции](02-core-concepts.md) |
| Полный жизненный цикл запроса | [Жизненный цикл запроса](03-request-lifecycle.md) |
| Каталог встроенных инструментов | [Инструменты](04-tools.md) |
| Хранилища сессий | [Хранилища сессий](05-session-stores.md) |
| Все 14 стратегий планирования | [Обзор стратегий](strategies/00-overview.md) |
| Все настройки | [Справочник конфигурации](13-configuration-reference.md) |
