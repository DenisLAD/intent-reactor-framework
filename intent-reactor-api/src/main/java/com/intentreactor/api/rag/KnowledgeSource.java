package com.intentreactor.api.rag;

import java.util.List;

/**
 * Strategy interface for knowledge retrieval backends used by {@code KnowledgeSearchTool}.
 *
 * <p>Any Spring bean that implements this interface is automatically discovered and
 * registered with the {@code KnowledgeSearchTool}. Multiple sources can coexist —
 * the tool queries all of them (or a named subset) and aggregates the results.
 *
 * <p>Built-in implementations provided by the {@code intent-reactor-rag} module:
 * <ul>
 *   <li>{@code InMemoryKnowledgeSource} — an in-memory list; populated programmatically
 *       via {@code add(KnowledgeDocument)}; keyword search</li>
 *   <li>{@code FileSystemKnowledgeSource} — scans a directory for text/markdown files
 *       and searches by keyword; enabled via
 *       {@code intent-reactor.rag.filesystem.enabled=true}</li>
 *   <li>{@code JdbcKnowledgeSource} — executes a {@code LIKE}-based SQL query against
 *       a configured table; enabled via {@code intent-reactor.rag.jdbc.enabled=true}</li>
 * </ul>
 *
 * <h2>Implementing a custom knowledge source</h2>
 * <pre>{@code
 * @Component
 * public class ChromaKnowledgeSource implements KnowledgeSource {
 *
 *     private final ChromaVectorStore store;
 *
 *     public ChromaKnowledgeSource(ChromaVectorStore store) {
 *         this.store = store;
 *     }
 *
 *     @Override
 *     public String getName() { return "chroma"; }
 *
 *     @Override
 *     public String getDescription() {
 *         return "Product knowledge base stored in Chroma vector store";
 *     }
 *
 *     @Override
 *     public List<KnowledgeDocument> search(KnowledgeQuery query) {
 *         List<Document> docs = store.similaritySearch(
 *             SearchRequest.query(query.getText()).withTopK(query.getMaxResults()));
 *         return docs.stream()
 *             .map(d -> KnowledgeDocument.builder()
 *                 .id(d.getId())
 *                 .content(d.getContent())
 *                 .score(d.getMetadata().containsKey("distance")
 *                     ? 1.0 - (double) d.getMetadata().get("distance") : -1.0)
 *                 .metadata(d.getMetadata())
 *                 .build())
 *             .toList();
 *     }
 *
 *     @Override
 *     public boolean supportsSemanticSearch() { return true; }
 * }
 * }</pre>
 *
 * <p><strong>Thread safety:</strong> {@link #search(KnowledgeQuery)} may be called
 * concurrently from multiple sessions. Implementations must be thread-safe.
 *
 * @see KnowledgeDocument
 * @see KnowledgeQuery
 */
public interface KnowledgeSource {

    /**
     * Returns the unique name that identifies this source.
     *
     * <p>Used in tool results, logs, and the {@code sources} filter parameter of
     * {@code KnowledgeSearchTool}. Use lowercase, underscore-separated identifiers
     * (e.g., {@code "product_catalog"}, {@code "support_docs"}).
     *
     * @return the source name; never {@code null} or blank
     */
    String getName();

    /**
     * Returns a short description of the knowledge this source contains.
     *
     * <p>Included in the {@code KnowledgeSearchTool} description that is sent to
     * the LLM. A precise description helps the planner choose which source to query.
     *
     * @return the source description; never {@code null}
     */
    String getDescription();

    /**
     * Searches for documents relevant to the given query.
     *
     * <p>Implementations must catch all checked exceptions internally and return
     * an empty list (or a list with a single error document) rather than propagating
     * exceptions to the caller.
     *
     * <p>The returned list may contain at most {@link KnowledgeQuery#getMaxResults()}
     * entries, though sources may return fewer.
     *
     * @param query the search request; never {@code null}
     * @return a non-null, possibly empty list of matching {@link KnowledgeDocument} instances;
     * sorted by relevance descending when the source computes scores
     */
    List<KnowledgeDocument> search(KnowledgeQuery query);

    /**
     * Returns {@code true} if this source uses semantic (vector/embedding) search.
     *
     * <p>This is a hint for the {@code KnowledgeSearchTool} description and for
     * future routing logic. The default implementation returns {@code false}
     * (keyword-based search).
     *
     * @return {@code true} for semantic sources, {@code false} for keyword sources
     */
    default boolean supportsSemanticSearch() {
        return false;
    }
}
