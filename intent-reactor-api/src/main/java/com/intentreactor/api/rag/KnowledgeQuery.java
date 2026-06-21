package com.intentreactor.api.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates a search request passed to a {@link KnowledgeSource}.
 *
 * <p>At minimum a query contains a {@link #text} — the natural-language
 * search string. Sources may optionally honour {@link #maxResults} and
 * {@link #filters}.
 *
 * <h2>Building a query</h2>
 * <pre>{@code
 * KnowledgeQuery query = KnowledgeQuery.builder()
 *     .text("vacuum flask temperature range")
 *     .maxResults(3)
 *     .filter("category", "kitchenware")
 *     .build();
 * }</pre>
 *
 * @see KnowledgeSource
 * @see KnowledgeDocument
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeQuery {

    /**
     * The natural-language text to search for.
     *
     * @return the query text; never {@code null} or blank
     */
    private String text;

    /**
     * Maximum number of {@link KnowledgeDocument} instances to return.
     *
     * <p>A value of {@code 0} or less is treated as "use the source default".
     * {@code KnowledgeSearchTool} passes its {@code max_results} parameter
     * here; the global default is {@code 5}.
     */
    @Builder.Default
    private int maxResults = 5;

    /**
     * Optional metadata filters.
     *
     * <p>Sources that support metadata filtering (e.g., a JDBC source with
     * indexed columns) may use these entries to narrow results.
     * Sources that do not support filtering must ignore this map.
     *
     * @return a map of filter key-value pairs; never {@code null}, may be empty
     */
    @Builder.Default
    private Map<String, Object> filters = new HashMap<>();

    /**
     * Convenience method — adds a single filter entry and returns {@code this}.
     *
     * @param key   the filter key; must not be {@code null}
     * @param value the filter value; must not be {@code null}
     * @return this query instance
     */
    public KnowledgeQuery filter(String key, Object value) {
        this.filters.put(key, value);
        return this;
    }
}
