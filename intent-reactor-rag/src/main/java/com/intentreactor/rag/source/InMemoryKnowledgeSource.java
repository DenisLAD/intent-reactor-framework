package com.intentreactor.rag.source;

import com.intentreactor.api.rag.KnowledgeDocument;
import com.intentreactor.api.rag.KnowledgeQuery;
import com.intentreactor.api.rag.KnowledgeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link KnowledgeSource} backed by a thread-safe document list.
 *
 * <p>This source is not registered automatically — declare it as an explicit
 * {@code @Bean} and populate it with documents at startup:
 *
 * <pre>{@code
 * @Bean
 * public InMemoryKnowledgeSource productFaqSource() {
 *     InMemoryKnowledgeSource source = new InMemoryKnowledgeSource(
 *         "product_faq", "Frequently asked questions about our product catalogue");
 *     source.add(KnowledgeDocument.builder()
 *         .id("faq-1")
 *         .content("Our return policy allows returns within 30 days of purchase.")
 *         .build());
 *     return source;
 * }
 * }</pre>
 *
 * <h2>Search algorithm</h2>
 * <p>Performs case-insensitive substring search on the {@code content} field.
 * Score is computed as the ratio of non-overlapping keyword occurrences to
 * document length (word count), normalised to {@code [0.0, 1.0]}. Documents
 * with zero matches are excluded from the result.
 *
 * @see KnowledgeSource
 */
public class InMemoryKnowledgeSource implements KnowledgeSource {

    private final String name;
    private final String description;
    private final CopyOnWriteArrayList<KnowledgeDocument> documents = new CopyOnWriteArrayList<>();

    /**
     * Creates a new in-memory knowledge source.
     *
     * @param name        unique snake_case identifier; never {@code null} or blank
     * @param description short description of the knowledge this source contains; never {@code null}
     */
    public InMemoryKnowledgeSource(String name, String description) {
        this.name = name;
        this.description = description;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * Adds a document to this source.
     *
     * <p>The operation is thread-safe; it is safe to call {@code add} concurrently
     * with {@link #search(KnowledgeQuery)}.
     *
     * @param document the document to add; must not be {@code null}
     */
    public void add(KnowledgeDocument document) {
        documents.add(document);
    }

    /**
     * Adds multiple documents to this source.
     *
     * @param docs the documents to add; must not be {@code null}
     */
    public void addAll(List<KnowledgeDocument> docs) {
        documents.addAll(docs);
    }

    /**
     * Removes all documents from this source.
     */
    public void clear() {
        documents.clear();
    }

    /**
     * Returns the number of documents currently in this source.
     *
     * @return document count; always {@code >= 0}
     */
    public int size() {
        return documents.size();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public List<KnowledgeDocument> search(KnowledgeQuery query) {
        if (query == null || query.getText() == null || query.getText().isBlank()) {
            return List.of();
        }
        String needle = query.getText().toLowerCase(Locale.ROOT);
        int limit = query.getMaxResults() > 0 ? query.getMaxResults() : 5;

        List<KnowledgeDocument> results = new ArrayList<>();
        for (KnowledgeDocument doc : documents) {
            if (doc.getContent() == null) continue;
            String haystack = doc.getContent().toLowerCase(Locale.ROOT);
            int occurrences = countOccurrences(haystack, needle);
            if (occurrences == 0) continue;

            int wordCount = Math.max(1, doc.getContent().split("\\s+").length);
            double score = Math.min(1.0, (double) occurrences / (wordCount * 0.01 + 1));

            KnowledgeDocument hit = KnowledgeDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .sourceName(name)
                    .score(score)
                    .metadata(doc.getMetadata())
                    .build();
            results.add(hit);
        }

        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results.stream().limit(limit).toList();
    }
}
