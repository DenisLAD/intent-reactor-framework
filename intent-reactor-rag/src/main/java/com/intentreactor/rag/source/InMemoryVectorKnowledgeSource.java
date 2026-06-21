package com.intentreactor.rag.source;

import com.intentreactor.api.rag.KnowledgeDocument;
import com.intentreactor.api.rag.KnowledgeQuery;
import com.intentreactor.api.rag.KnowledgeSource;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link KnowledgeSource} that uses vector embeddings for semantic search.
 *
 * <p>Documents are embedded once at {@link #add} time using a Spring AI
 * {@link EmbeddingModel}. Queries are also embedded on each {@link #search} call.
 * Relevance is computed via cosine similarity, normalised to {@code [0.0, 1.0]}.
 *
 * <p>This source is not registered automatically — declare it as an explicit
 * {@code @Bean} and populate it with documents at startup:
 *
 * <pre>{@code
 * @Bean
 * public InMemoryVectorKnowledgeSource techKnowledge(EmbeddingModel embeddingModel) {
 *     var source = new InMemoryVectorKnowledgeSource(
 *         "tech_kb", "Technical architecture concepts", embeddingModel);
 *     source.add(KnowledgeDocument.builder()
 *         .id("scale-1")
 *         .content("Horizontal scaling adds more server instances to distribute load.")
 *         .build());
 *     source.add(KnowledgeDocument.builder()
 *         .id("cb-1")
 *         .content("Circuit breaker pattern stops cascading failures in distributed systems.")
 *         .build());
 *     return source;
 * }
 * }</pre>
 *
 * <p>Unlike {@link InMemoryKnowledgeSource}, semantic search finds documents by meaning
 * even when the query and document use different words (e.g. "how to scale a service"
 * matches "horizontal scaling adds more server instances").
 *
 * <h2>Thread safety</h2>
 * <p>{@link #search} is thread-safe for concurrent reads. {@link #add} is thread-safe
 * with respect to search but concurrent calls to {@code add} themselves are not
 * synchronised — populate the source at startup before serving traffic.
 *
 * @see InMemoryKnowledgeSource
 * @see KnowledgeSource
 */
public class InMemoryVectorKnowledgeSource implements KnowledgeSource {

    private final String name;
    private final String description;
    private final EmbeddingModel embeddingModel;
    private final CopyOnWriteArrayList<KnowledgeDocument> documents = new CopyOnWriteArrayList<>();
    private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();

    /**
     * Creates a new vector knowledge source.
     *
     * @param name           unique snake_case identifier; never {@code null} or blank
     * @param description    short description of the knowledge this source contains
     * @param embeddingModel the Spring AI embedding model used to vectorise text
     */
    public InMemoryVectorKnowledgeSource(String name, String description, EmbeddingModel embeddingModel) {
        this.name = name;
        this.description = description;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Adds a document and pre-computes its embedding.
     *
     * <p>The embedding is computed synchronously via the configured {@link EmbeddingModel}.
     * Prefer bulk-loading via {@link #addAll} when adding many documents at startup
     * to reduce round-trips to the embedding endpoint.
     *
     * @param document the document to index; {@code id} and {@code content} must not be {@code null}
     * @throws RuntimeException if the embedding model call fails
     */
    public void add(KnowledgeDocument document) {
        float[] vec = embeddingModel.embed(document.getContent());
        embeddings.put(document.getId(), vec);
        documents.add(document);
    }

    /**
     * Adds multiple documents, embedding each in sequence.
     *
     * @param docs the documents to add; must not be {@code null}
     */
    public void addAll(List<KnowledgeDocument> docs) {
        for (KnowledgeDocument doc : docs) {
            add(doc);
        }
    }

    /**
     * Removes all documents and their cached embeddings.
     */
    public void clear() {
        documents.clear();
        embeddings.clear();
    }

    /**
     * Returns the number of indexed documents.
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

    /**
     * Returns {@code true} — this source performs semantic vector search.
     */
    @Override
    public boolean supportsSemanticSearch() {
        return true;
    }

    @Override
    public List<KnowledgeDocument> search(KnowledgeQuery query) {
        if (query == null || query.getText() == null || query.getText().isBlank() || documents.isEmpty()) {
            return List.of();
        }
        int limit = query.getMaxResults() > 0 ? query.getMaxResults() : 5;
        float[] queryVec = embeddingModel.embed(query.getText());

        List<KnowledgeDocument> results = new ArrayList<>();
        for (KnowledgeDocument doc : documents) {
            float[] docVec = embeddings.get(doc.getId());
            if (docVec == null) continue;
            double score = normalizeScore(cosineSimilarity(queryVec, docVec));
            results.add(KnowledgeDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .sourceName(name)
                    .score(score)
                    .metadata(doc.getMetadata())
                    .build());
        }

        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results.stream().limit(limit).toList();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dotProduct += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // Maps cosine similarity [-1, 1] → [0, 1] to align with KnowledgeDocument.score contract.
    private double normalizeScore(double cosine) {
        return Math.max(0.0, Math.min(1.0, (cosine + 1.0) / 2.0));
    }
}
