# Инструменты

## Интерфейс Tool

```java
public interface Tool {
    String getName();           // snake_case, глобально уникальное имя
    String getDescription();    // текст для LLM при выборе инструмента
    String getSchema();         // JSON Schema параметров
    ToolResult execute(ToolInput input);
    boolean isRisky();
    boolean isGenerator();
}
```

**Соглашение об именовании:** используйте `snake_case`. Имя включается в системный промпт в том виде, как написано. LLM вызывает инструмент именно по этому имени.

**Потокобезопасность:** инструменты — синглтон-бины Spring. `execute()` должен быть потокобезопасным. Не храните изменяемое состояние в полях.

---

## ToolResult

```java
// Успешный результат
ToolResult.ok("Файл прочитан: ...")

// Ошибка (LLM видит сообщение об ошибке и может попробовать другой подход)
ToolResult.error("Файл не найден: config.yaml")
```

`ToolResult.error()` не выбрасывает исключение и не прерывает планирование. LLM получает сообщение об ошибке как наблюдение и может скорректировать своё поведение.

---

## ToolInput

```java
public class ToolInput {
    Map<String, Object> getParameters();  // поля из JSON Schema
    String getSessionId();                // идентификатор текущей сессии
}
```

Значения параметров уже десериализованы из JSON в соответствующие Java-типы (String, Integer, Boolean, List, Map).

---

## Рискованные инструменты: isRisky()

Когда `isRisky()` возвращает `true` и `autonomous=false`, выполнение приостанавливается до получения подтверждения:

```java
@Override
public boolean isRisky() {
    return true;  // удаление файла, запись в БД, отправка email и т.д.
}
```

Подробнее — в [Потоке подтверждения](07-confirmation-flow.md).

---

## Инструменты-генераторы: isGenerator()

Инструменты-генераторы (`isGenerator() = true`) невидимы для LLM. Они используются фреймворком для создания других инструментов во время выполнения. Единственный встроенный пример — `DynamicScriptTool`.

---

## SimulatableTool

Реализуйте `SimulatableTool` для инструментов, которые могут безопасно работать в режиме сухого прогона (нужно для стратегии LATS):

```java
@Component
public class SendEmailTool implements Tool, SimulatableTool {

    @Override
    public ToolResult execute(ToolInput input) {
        emailService.send(input.getParameters());
        return ToolResult.ok("Email отправлен.");
    }

    @Override
    public ToolResult simulate(ToolInput input) {
        // Симуляция без отправки реального письма
        return ToolResult.ok("Симуляция: email будет отправлен на "
            + input.getParameters().get("to"));
    }

    // ...
}
```

---

## Кастомный ToolProvider

Для фильтрации инструментов по контексту сессии:

```java
@Component
@Primary
public class FeatureFlagToolProvider implements ToolProvider {

    private final List<Tool> allTools;

    @Override
    public List<Tool> getTools(SessionState session) {
        return allTools.stream()
            .filter(t -> featureEnabled(t.getName(), session))
            .collect(Collectors.toList());
    }
}
```

---

## Каталог Tool Commons

Модуль `intent-reactor-tool-commons` предоставляет готовые инструменты:

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-tool-commons</artifactId>
    <version>0.1.6</version>
</dependency>
```

| Имя инструмента | Параметры | Рискованный | Описание |
|---|---|---|---|
| `read_file` | `path` | Нет | Читает содержимое файла |
| `write_file` | `path`, `content` | **Да** | Создаёт или перезаписывает файл |
| `edit_file` | `path`, `old_string`, `new_string` | **Да** | Точечная замена строки в файле |
| `glob` | `pattern`, `path?` | Нет | Поиск файлов по шаблону glob |
| `grep` | `pattern`, `path?`, `glob?` | Нет | Поиск содержимого по регулярному выражению |
| `calculator` | `expression` | Нет | Вычисляет математическое выражение |
| `datetime` | `format?`, `timezone?` | Нет | Возвращает текущую дату/время |
| `web_fetch` | `url`, `method?`, `headers?`, `body?` | Нет | HTTP-запрос к внешнему URL |
| `ask_user` | `question` | Нет | Запрашивает уточнение у пользователя |
| `file_content_extractor` | `path` | Нет | Извлекает текст из PDF, DOCX, XLSX |
| `apply_patch` | `path`, `patch` | **Да** | Применяет унифицированный патч к файлу |
| `todo_write_tool` | `todos` | Нет | Создаёт/обновляет список задач в сессии |
| `markdown_file_scanner_tool` | `path`, `glob?` | Нет | Сканирует MD-файлы, возвращает структуру |
| `take_snapshot` | — | Нет | Делает снимок состояния браузера (для автоматизации) |

Отключить конкретный инструмент:

```yaml
intent-reactor:
  tools:
    write-file:
      enabled: false
```
