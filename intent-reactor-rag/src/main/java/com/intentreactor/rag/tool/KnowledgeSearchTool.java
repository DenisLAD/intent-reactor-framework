package com.intentreactor.rag.tool;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.api.rag.KnowledgeDocument;
import com.intentreactor.api.rag.KnowledgeQuery;
import com.intentreactor.api.rag.KnowledgeSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link Tool} that retrieves relevant knowledge from all registered
 * {@link KnowledgeSource} backends and returns the aggregated results
 * as formatted text ready for LLM consumption.
 *
 * <p>The tool is registered automatically by {@code RagAutoConfiguration}
 * when at least one {@code KnowledgeSource} bean is present on the classpath.
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li>{@code query} (string, required) — the natural-language search query</li>
 *   <li>{@code sources} (array of strings, optional) — names of specific sources to
 *       query; omit to query all registered sources</li>
 *   <li>{@code max_results} (integer, optional, default 5) — maximum number of
 *       documents to include in the result</li>
 * </ul>
 *
 * <h2>Output format</h2>
 * <pre>
 * [source_name] doc_id (score=0.87):
 * &lt;content&gt;
 * ---
 * [source_name] doc_id2 (score=0.74):
 * &lt;content&gt;
 * ---
 * </pre>
 *
 * @see KnowledgeSource
 * @see KnowledgeDocument
 */
public class KnowledgeSearchTool implements Tool {

    private final List<KnowledgeSource> sources;

    public KnowledgeSearchTool(List<KnowledgeSource> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public String getName() {
        return "knowledge_search";
    }

    @Override
    public String getDescription() {
        if (sources.isEmpty()) {
            return "Search the knowledge base for relevant information. No sources currently registered.";
        }
        String sourceList = sources.stream()
                .map(s -> s.getName() + " (" + s.getDescription() + ")")
                .collect(Collectors.joining("; "));
        return "Search the knowledge base for relevant information and return matching documents. " +
                "Available sources: " + sourceList + ". " +
                "Use the 'sources' parameter to restrict to specific sources by name.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Natural-language search query"
                        ),
                        "sources", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Names of specific knowledge sources to query (omit for all sources)"
                        ),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum number of documents to return (default: 5)"
                        )
                ),
                "required", List.of("query")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        if (sources.isEmpty()) {
            return ToolResult.error("No KnowledgeSource beans registered");
        }

        String queryText = (String) input.getParameters().get("query");
        if (queryText == null || queryText.isBlank()) {
            return ToolResult.error("Parameter 'query' is required");
        }

        Object maxResultsParam = input.getParameters().get("max_results");
        int maxResults = maxResultsParam instanceof Number n ? n.intValue() : 5;
        if (maxResults <= 0) maxResults = 5;

        @SuppressWarnings("unchecked")
        List<String> sourceFilter = (List<String>) input.getParameters().get("sources");
        Set<String> allowedSources = sourceFilter != null && !sourceFilter.isEmpty()
                ? Set.copyOf(sourceFilter)
                : null;

        KnowledgeQuery query = KnowledgeQuery.builder()
                .text(queryText)
                .maxResults(maxResults)
                .build();

        List<KnowledgeDocument> allDocs = new ArrayList<>();
        for (KnowledgeSource source : sources) {
            if (allowedSources != null && !allowedSources.contains(source.getName())) {
                continue;
            }
            try {
                List<KnowledgeDocument> docs = source.search(query);
                if (docs != null) {
                    for (KnowledgeDocument doc : docs) {
                        if (doc.getSourceName() == null) {
                            doc.setSourceName(source.getName());
                        }
                        allDocs.add(doc);
                    }
                }
            } catch (Exception e) {
                allDocs.add(KnowledgeDocument.builder()
                        .id("error")
                        .sourceName(source.getName())
                        .content("Error querying source '" + source.getName() + "': " + e.getMessage())
                        .score(-1.0)
                        .build());
            }
        }

        if (allDocs.isEmpty()) {
            return ToolResult.ok("No documents found matching: " + queryText);
        }

        List<KnowledgeDocument> sorted = allDocs.stream()
                .sorted(Comparator.comparingDouble(KnowledgeDocument::getScore).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        for (KnowledgeDocument doc : sorted) {
            sb.append("[").append(doc.getSourceName()).append("] ")
                    .append(doc.getId() != null ? doc.getId() : "?");
            if (doc.getScore() >= 0) {
                sb.append(String.format(" (score=%.2f)", doc.getScore()));
            }
            sb.append(":\n");
            sb.append(doc.getContent() != null ? doc.getContent() : "");
            sb.append("\n---\n");
        }

        return ToolResult.ok(sb.toString().trim());
    }

    @Override
    public boolean isRisky() {
        return false;
    }
}
