# RAG — Retrieval-Augmented Generation

The `intent-reactor-rag` module adds a `knowledge_search` tool that lets the planner retrieve relevant documents from one or more knowledge sources before generating an answer.

---

## Dependency

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-rag</artifactId>
    <version>0.1.6</version>
</dependency>
```

---

## How it works

1. The `knowledge_search` tool is registered automatically when at least one `KnowledgeSource` bean is present.
2. The planner can invoke `knowledge_search` just like any other tool.
3. The tool queries all registered sources (or a filtered subset), aggregates results, sorts by score, and returns them as formatted text that the LLM uses in its next reasoning step.

---

## The knowledge_search tool

**Tool name:** `knowledge_search`

**Parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `query` | string | Yes | Natural-language search query |
| `sources` | string[] | No | Filter to specific source names; empty = all sources |
| `max_results` | integer | No | Limit results (default: `rag.max-results`) |

**Output format:**

```
[source-name] doc-id (score=0.87):
<document content>
---
[source-name] doc-id (score=0.72):
<document content>
---
```

---

## KnowledgeSource interface

Implement this interface to create a custom knowledge source:

```java
public interface KnowledgeSource {
    String getName();                            // unique source identifier
    String getDescription();                     // shown in tool description
    List<KnowledgeDocument> search(KnowledgeQuery query);
    boolean supportsSemanticSearch();            // true = embedding-based
}
```

`KnowledgeQuery` fields:
- `text` — search text (required)
- `maxResults` — result limit (default 5)
- `filters` — optional `Map<String, Object>` for metadata filtering

`KnowledgeDocument` fields:
- `id` — document identifier
- `content` — text content (100–500 tokens recommended per chunk)
- `sourceName` — which source produced this document
- `score` — relevance score (0.0–1.0; `-1.0` means not computed)
- `metadata` — arbitrary `Map<String, Object>` (author, category, URL, etc.)

---

## Built-in knowledge sources

### InMemoryKnowledgeSource

Keyword search over a list of documents held in memory. Best for small, static knowledge bases loaded at startup.

```java
@Bean
public InMemoryKnowledgeSource productKnowledge() {
    InMemoryKnowledgeSource source = new InMemoryKnowledgeSource("products",
        "Internal product catalogue");
    source.add(KnowledgeDocument.builder()
        .id("prod-001")
        .content("Widget Pro: 4K sensor, 120fps, IP67 waterproof, $299.")
        .sourceName("products")
        .build());
    // ... add more documents ...
    return source;
}
```

Score formula: `min(1.0, occurrences / (wordCount × 0.01 + 1))` — higher for shorter documents with more keyword matches.

### FileSystemKnowledgeSource

Scans a directory for files matching a glob pattern and performs keyword search with context lines.

```yaml
intent-reactor:
  rag:
    filesystem:
      enabled: true
      path: ./knowledge          # root directory
      glob: "**/*.{txt,md}"      # file filter
      max-file-size-kb: 100      # skip larger files (0 = no limit)
```

Each match returns the matching line plus up to 3 context lines above and below. Document ID format: `relative/path/to/file.md:42` (file + line number). Fixed score: `0.5`.

### JdbcKnowledgeSource

Executes a `LIKE` query against a database table.

```yaml
intent-reactor:
  rag:
    jdbc:
      enabled: true
      table: knowledge_documents          # table name
      content-column: content             # text column for search
      id-column: id                       # primary key column
      metadata-columns:                   # extra columns included in metadata
        - category
        - author
```

Required table schema:

```sql
CREATE TABLE knowledge_documents (
    id       VARCHAR(255) PRIMARY KEY,
    content  TEXT         NOT NULL,
    category VARCHAR(100),            -- optional metadata column
    author   VARCHAR(100)             -- optional metadata column
);
```

Score: `-1.0` (not computed for SQL LIKE queries).

### InMemoryVectorKnowledgeSource

Semantic search using Spring AI's `EmbeddingModel`. Returns `supportsSemanticSearch() = true`.

```java
@Bean
public InMemoryVectorKnowledgeSource semanticKnowledge(EmbeddingModel embeddingModel) {
    InMemoryVectorKnowledgeSource source = new InMemoryVectorKnowledgeSource(
        "semantic-docs", "Semantic document search", embeddingModel);
    source.add(KnowledgeDocument.builder()
        .id("faq-001")
        .content("Our return policy allows returns within 30 days with receipt.")
        .sourceName("semantic-docs")
        .build());
    return source;
}
```

Embeddings are computed lazily on first search and cached. Score is cosine similarity normalized to `[0.0, 1.0]`: `(cosine + 1.0) / 2.0`.

> Populate documents at startup — concurrent additions are not synchronized.

---

## Custom KnowledgeSource

```java
@Component
public class ElasticsearchKnowledgeSource implements KnowledgeSource {

    private final ElasticsearchClient es;

    @Override
    public String getName() { return "elasticsearch"; }

    @Override
    public String getDescription() { return "Full-text search over Elasticsearch index"; }

    @Override
    public List<KnowledgeDocument> search(KnowledgeQuery query) {
        // ... execute ES query, map hits to KnowledgeDocument ...
    }

    @Override
    public boolean supportsSemanticSearch() { return false; }
}
```

Any `@Component` or `@Bean` implementing `KnowledgeSource` is automatically picked up by `RagAutoConfiguration`.

---

## Global RAG configuration

```yaml
intent-reactor:
  rag:
    enabled: true         # set to false to disable the entire module
    max-results: 5        # default max_results for knowledge_search tool
```
