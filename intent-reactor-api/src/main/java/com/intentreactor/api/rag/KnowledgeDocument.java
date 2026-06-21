package com.intentreactor.api.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * A single piece of knowledge retrieved from a {@link KnowledgeSource}.
 *
 * <p>The {@link #content} field is the human-readable text that will be
 * injected into the LLM context by {@code KnowledgeSearchTool}. Keep it
 * focused and concise — large content chunks consume context tokens and
 * may reduce planner accuracy.
 *
 * <p>The {@link #metadata} map carries source-specific attributes such as
 * file path, table name, document tags, or creation date. Metadata is
 * included in the tool result so the LLM can cite sources.
 *
 * <h2>Creating a KnowledgeDocument</h2>
 * <pre>{@code
 * KnowledgeDocument doc = KnowledgeDocument.builder()
 *     .id("product-42")
 *     .content("Product 42 is a 10-litre stainless steel vacuum flask, rated for -30°C to +120°C.")
 *     .sourceName("product-catalog")
 *     .score(0.91)
 *     .metadata(Map.of("category", "kitchenware", "sku", "FL-10-SS"))
 *     .build();
 * }</pre>
 *
 * @see KnowledgeSource
 * @see KnowledgeQuery
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument {

    /**
     * Unique identifier of this document within its source.
     *
     * <p>May be a UUID, a file path, a database row ID, or any other
     * unique string. Used for deduplication and citation.
     *
     * @return the document identifier; never {@code null}
     */
    private String id;

    /**
     * The human-readable text of this document.
     *
     * <p>This text is embedded verbatim into the LLM prompt. Aim for
     * 100–500 tokens. If your source returns large texts, truncate or
     * chunk them before creating the document.
     *
     * @return the document content; never {@code null}
     */
    private String content;

    /**
     * The name of the {@link KnowledgeSource} that produced this document.
     *
     * <p>Set automatically by {@code KnowledgeSearchTool} after aggregating
     * results; {@link KnowledgeSource} implementations may also set it.
     *
     * @return the source name; may be {@code null} when constructed directly
     */
    private String sourceName;

    /**
     * Relevance score in the range {@code [0.0, 1.0]}, or {@code -1.0} when
     * the source does not compute a relevance score.
     *
     * <p>Higher values indicate greater relevance to the query.
     * {@code KnowledgeSearchTool} sorts results by score (descending) before
     * trimming to {@code max_results}.
     */
    @Builder.Default
    private double score = -1.0;

    /**
     * Arbitrary source-specific attributes (e.g., file path, table name, tags).
     *
     * <p>Serialised as a JSON object in the tool result so the LLM can
     * cite or filter by these attributes.
     *
     * @return a mutable map of metadata; never {@code null}
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
