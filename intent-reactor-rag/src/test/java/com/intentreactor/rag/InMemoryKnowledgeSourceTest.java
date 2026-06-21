package com.intentreactor.rag;

import com.intentreactor.api.rag.KnowledgeDocument;
import com.intentreactor.api.rag.KnowledgeQuery;
import com.intentreactor.rag.source.InMemoryKnowledgeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryKnowledgeSourceTest {

    private InMemoryKnowledgeSource source;

    @BeforeEach
    void setUp() {
        source = new InMemoryKnowledgeSource("test", "Test source");
        source.add(KnowledgeDocument.builder().id("doc1").content("The quick brown fox jumps over the lazy dog").build());
        source.add(KnowledgeDocument.builder().id("doc2").content("Spring Boot makes it easy to create stand-alone applications").build());
        source.add(KnowledgeDocument.builder().id("doc3").content("IntentReactor is a Spring Boot framework for LLM planning").build());
    }

    @Test
    void search_returnsMatchingDocuments() {
        List<KnowledgeDocument> results = source.search(
                KnowledgeQuery.builder().text("Spring Boot").maxResults(5).build());

        assertThat(results).hasSize(2);
        assertThat(results).extracting(KnowledgeDocument::getId).containsExactlyInAnyOrder("doc2", "doc3");
    }

    @Test
    void search_isCaseInsensitive() {
        List<KnowledgeDocument> results = source.search(
                KnowledgeQuery.builder().text("spring boot").maxResults(5).build());

        assertThat(results).hasSize(2);
    }

    @Test
    void search_respectsMaxResults() {
        source.add(KnowledgeDocument.builder().id("doc4").content("Spring Boot is great").build());

        List<KnowledgeDocument> results = source.search(
                KnowledgeQuery.builder().text("Spring Boot").maxResults(2).build());

        assertThat(results).hasSize(2);
    }

    @Test
    void search_returnsEmptyWhenNoMatch() {
        List<KnowledgeDocument> results = source.search(
                KnowledgeQuery.builder().text("kubernetes").maxResults(5).build());

        assertThat(results).isEmpty();
    }

    @Test
    void search_returnsEmptyOnBlankQuery() {
        assertThat(source.search(KnowledgeQuery.builder().text("").build())).isEmpty();
        assertThat(source.search(KnowledgeQuery.builder().text("  ").build())).isEmpty();
        assertThat(source.search(null)).isEmpty();
    }

    @Test
    void search_setsSourceName() {
        List<KnowledgeDocument> results = source.search(
                KnowledgeQuery.builder().text("fox").maxResults(5).build());

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getSourceName()).isEqualTo("test");
    }

    @Test
    void search_scoresAreInDescendingOrder() {
        for (int i = 0; i < 5; i++) {
            source.add(KnowledgeDocument.builder()
                    .id("extra" + i)
                    .content("Spring Boot Spring Boot Spring Boot occurrence " + i)
                    .build());
        }
        List<KnowledgeDocument> results = source.search(
                KnowledgeQuery.builder().text("Spring Boot").maxResults(10).build());

        for (int i = 0; i < results.size() - 1; i++) {
            assertThat(results.get(i).getScore()).isGreaterThanOrEqualTo(results.get(i + 1).getScore());
        }
    }

    @Test
    void addAll_addsMultipleDocuments() {
        InMemoryKnowledgeSource fresh = new InMemoryKnowledgeSource("s", "d");
        fresh.addAll(List.of(
                KnowledgeDocument.builder().id("a").content("hello world").build(),
                KnowledgeDocument.builder().id("b").content("hello java").build()
        ));
        assertThat(fresh.size()).isEqualTo(2);
    }

    @Test
    void clear_removesAllDocuments() {
        source.clear();
        assertThat(source.size()).isZero();
        assertThat(source.search(KnowledgeQuery.builder().text("Spring Boot").build())).isEmpty();
    }
}
