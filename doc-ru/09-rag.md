# RAG — Дополненная генерация с извлечением

Модуль `intent-reactor-rag` добавляет инструмент `knowledge_search`, позволяющий планировщику извлекать релевантные документы из одного или нескольких источников знаний перед формированием ответа.

---

## Зависимость

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-rag</artifactId>
    <version>0.1.6</version>
</dependency>
```

---

## Принцип работы

1. Инструмент `knowledge_search` регистрируется автоматически при наличии хотя бы одного бина `KnowledgeSource`.
2. Планировщик вызывает `knowledge_search` как любой другой инструмент.
3. Инструмент запрашивает все зарегистрированные источники (или отфильтрованное подмножество), агрегирует результаты, сортирует по релевантности и возвращает форматированный текст, который LLM использует на следующем шаге рассуждения.

---

## Инструмент knowledge_search

**Параметры:**

| Параметр | Тип | Обязательный | Описание |
|---|---|---|---|
| `query` | string | Да | Поисковый запрос на естественном языке |
| `sources` | string[] | Нет | Фильтр по именам источников; пусто = все источники |
| `max_results` | integer | Нет | Лимит результатов (по умолчанию: `rag.max-results`) |

**Формат вывода:**

```
[имя-источника] id-документа (score=0.87):
<содержимое документа>
---
[имя-источника] id-документа (score=0.72):
<содержимое документа>
---
```

---

## Интерфейс KnowledgeSource

```java
public interface KnowledgeSource {
    String getName();                            // уникальный идентификатор источника
    String getDescription();                     // отображается в описании инструмента
    List<KnowledgeDocument> search(KnowledgeQuery query);
    boolean supportsSemanticSearch();            // true = поиск на основе эмбеддингов
}
```

Поля `KnowledgeQuery`:
- `text` — текст запроса (обязательно)
- `maxResults` — лимит результатов (по умолчанию 5)
- `filters` — опциональный `Map<String, Object>` для фильтрации по метаданным

Поля `KnowledgeDocument`:
- `id` — идентификатор документа
- `content` — текстовое содержимое (рекомендуется 100–500 токенов на фрагмент)
- `sourceName` — имя источника, из которого получен документ
- `score` — оценка релевантности (0.0–1.0; `-1.0` = не вычислено)
- `metadata` — произвольный `Map<String, Object>` (автор, категория, URL и т.д.)

---

## Встроенные источники знаний

### InMemoryKnowledgeSource

Ключевой поиск по списку документов в памяти. Подходит для небольших статичных баз знаний, загружаемых при старте.

```java
@Bean
public InMemoryKnowledgeSource productKnowledge() {
    InMemoryKnowledgeSource source = new InMemoryKnowledgeSource("products",
        "Внутренний каталог продуктов");
    source.add(KnowledgeDocument.builder()
        .id("prod-001")
        .content("Widget Pro: сенсор 4K, 120fps, защита IP67, 299$.")
        .sourceName("products")
        .build());
    return source;
}
```

Формула оценки: `min(1.0, вхождения / (количество_слов × 0.01 + 1))` — выше для коротких документов с большим количеством совпадений.

### FileSystemKnowledgeSource

Сканирует директорию на файлы по шаблону glob и выполняет ключевой поиск с контекстными строками.

```yaml
intent-reactor:
  rag:
    filesystem:
      enabled: true
      path: ./knowledge          # корневая директория
      glob: "**/*.{txt,md}"      # фильтр файлов
      max-file-size-kb: 100      # пропускать файлы крупнее (0 = без ограничений)
```

Каждое совпадение возвращает строку с совпадением и до 3 контекстных строк выше и ниже. Формат идентификатора документа: `relative/path/to/file.md:42` (файл + номер строки). Фиксированная оценка: `0.5`.

### JdbcKnowledgeSource

Выполняет `LIKE`-запрос к таблице базы данных.

```yaml
intent-reactor:
  rag:
    jdbc:
      enabled: true
      table: knowledge_documents
      content-column: content
      id-column: id
      metadata-columns:
        - category
        - author
```

Схема таблицы:

```sql
CREATE TABLE knowledge_documents (
    id       VARCHAR(255) PRIMARY KEY,
    content  TEXT         NOT NULL,
    category VARCHAR(100),
    author   VARCHAR(100)
);
```

Оценка: `-1.0` (не вычисляется для SQL LIKE-запросов).

### InMemoryVectorKnowledgeSource

Семантический поиск с использованием `EmbeddingModel` из Spring AI. Возвращает `supportsSemanticSearch() = true`.

```java
@Bean
public InMemoryVectorKnowledgeSource semanticKnowledge(EmbeddingModel embeddingModel) {
    InMemoryVectorKnowledgeSource source = new InMemoryVectorKnowledgeSource(
        "semantic-docs", "Семантический поиск по документам", embeddingModel);
    source.add(KnowledgeDocument.builder()
        .id("faq-001")
        .content("Политика возврата допускает возврат в течение 30 дней с чеком.")
        .sourceName("semantic-docs")
        .build());
    return source;
}
```

Эмбеддинги вычисляются лениво при первом поиске и кешируются. Оценка — косинусное сходство, нормализованное к `[0.0, 1.0]`: `(cosine + 1.0) / 2.0`.

> Заполняйте документы при старте приложения — одновременные добавления не синхронизированы.

---

## Кастомный KnowledgeSource

```java
@Component
public class ElasticsearchKnowledgeSource implements KnowledgeSource {

    private final ElasticsearchClient es;

    @Override
    public String getName() { return "elasticsearch"; }

    @Override
    public String getDescription() { return "Полнотекстовый поиск по Elasticsearch-индексу"; }

    @Override
    public List<KnowledgeDocument> search(KnowledgeQuery query) {
        // ...выполнить ES-запрос, преобразовать хиты в KnowledgeDocument...
    }

    @Override
    public boolean supportsSemanticSearch() { return false; }
}
```

Любой `@Component` или `@Bean`, реализующий `KnowledgeSource`, автоматически подхватывается `RagAutoConfiguration`.

---

## Глобальная конфигурация RAG

```yaml
intent-reactor:
  rag:
    enabled: true       # false = полностью отключить модуль
    max-results: 5      # лимит результатов по умолчанию для knowledge_search
```
