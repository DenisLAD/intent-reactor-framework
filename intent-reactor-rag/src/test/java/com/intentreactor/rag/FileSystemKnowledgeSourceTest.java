package com.intentreactor.rag;

import com.intentreactor.api.rag.KnowledgeDocument;
import com.intentreactor.api.rag.KnowledgeQuery;
import com.intentreactor.rag.source.FileSystemKnowledgeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemKnowledgeSourceTest {

    @TempDir
    Path tempDir;

    private FileSystemKnowledgeSource source(String glob) {
        return new FileSystemKnowledgeSource("docs", "Test docs", tempDir, glob, 100);
    }

    @Test
    void search_findsKeywordInTextFile() throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "This is a knowledge base document.\nIntentReactor supports RAG.\nEnd of file.");

        List<KnowledgeDocument> results = source("*.txt").search(
                KnowledgeQuery.builder().text("RAG").maxResults(5).build());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).contains("RAG");
        assertThat(results.get(0).getSourceName()).isEqualTo("docs");
    }

    @Test
    void search_includesContextLines() throws IOException {
        Files.writeString(tempDir.resolve("doc.txt"),
                "Line 1\nLine 2\nKeyword here\nLine 4\nLine 5");

        List<KnowledgeDocument> results = source("*.txt").search(
                KnowledgeQuery.builder().text("Keyword").maxResults(5).build());

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getContent()).contains("Line 1").contains("Keyword here").contains("Line 4");
    }

    @Test
    void search_returnsEmptyForNonMatchingGlob() throws IOException {
        Files.writeString(tempDir.resolve("data.csv"), "col1,col2\nvalue with keyword,other");

        List<KnowledgeDocument> results = source("*.txt").search(
                KnowledgeQuery.builder().text("keyword").maxResults(5).build());

        assertThat(results).isEmpty();
    }

    @Test
    void search_skipsFilesExceedingSizeLimit() throws IOException {
        Path bigFile = tempDir.resolve("big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("some content with keyword here\n");
        Files.writeString(bigFile, sb.toString());

        FileSystemKnowledgeSource smallLimitSource =
                new FileSystemKnowledgeSource("docs", "d", tempDir, "*.txt", 1); // 1 KB limit

        List<KnowledgeDocument> results = smallLimitSource.search(
                KnowledgeQuery.builder().text("keyword").maxResults(5).build());

        assertThat(results).isEmpty();
    }

    @Test
    void search_returnsEmptyWhenDirectoryDoesNotExist() {
        FileSystemKnowledgeSource noDir = new FileSystemKnowledgeSource(
                "docs", "d", Path.of("/nonexistent/path"), "*.txt", 100);

        List<KnowledgeDocument> results = noDir.search(
                KnowledgeQuery.builder().text("anything").maxResults(5).build());

        assertThat(results).isEmpty();
    }

    @Test
    void search_isCaseInsensitive() throws IOException {
        Files.writeString(tempDir.resolve("doc.txt"), "The Spring Framework is great");

        List<KnowledgeDocument> results = source("*.txt").search(
                KnowledgeQuery.builder().text("spring framework").maxResults(5).build());

        assertThat(results).hasSize(1);
    }

    @Test
    void search_setsDocumentIdWithLineNumber() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "Line 1\nLine 2\nTarget line\nLine 4");

        List<KnowledgeDocument> results = source("*.txt").search(
                KnowledgeQuery.builder().text("Target").maxResults(5).build());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).contains("notes.txt").contains("3");
    }
}
