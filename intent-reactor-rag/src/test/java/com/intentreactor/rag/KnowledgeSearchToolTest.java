package com.intentreactor.rag;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.api.rag.KnowledgeDocument;
import com.intentreactor.api.rag.KnowledgeSource;
import com.intentreactor.rag.source.InMemoryKnowledgeSource;
import com.intentreactor.rag.tool.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSearchToolTest {

    private KnowledgeSearchTool toolWithSources(KnowledgeSource... sources) {
        return new KnowledgeSearchTool(List.of(sources));
    }

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput(params, "test-session");
    }

    @Test
    void execute_returnsFormattedResults() {
        InMemoryKnowledgeSource source = new InMemoryKnowledgeSource("faq", "FAQ");
        source.add(KnowledgeDocument.builder().id("q1").content("Return policy: 30 days").build());

        KnowledgeSearchTool tool = toolWithSources(source);
        ToolResult result = tool.execute(input(Map.of("query", "return policy")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("[faq]").contains("q1").contains("Return policy");
    }

    @Test
    void execute_errorOnMissingQuery() {
        KnowledgeSearchTool tool = toolWithSources(new InMemoryKnowledgeSource("s", "d"));
        ToolResult result = tool.execute(input(Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("query");
    }

    @Test
    void execute_errorWhenNoSources() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(List.of());
        ToolResult result = tool.execute(input(Map.of("query", "anything")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("KnowledgeSource");
    }

    @Test
    void execute_noMatchReturnsOkWithMessage() {
        InMemoryKnowledgeSource source = new InMemoryKnowledgeSource("s", "d");
        source.add(KnowledgeDocument.builder().id("x").content("Spring Boot rocks").build());

        ToolResult result = toolWithSources(source).execute(input(Map.of("query", "kubernetes")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("No documents found");
    }

    @Test
    void execute_filtersSourcesByName() {
        InMemoryKnowledgeSource faq = new InMemoryKnowledgeSource("faq", "FAQ");
        faq.add(KnowledgeDocument.builder().id("f1").content("FAQ: return policy").build());

        InMemoryKnowledgeSource blog = new InMemoryKnowledgeSource("blog", "Blog");
        blog.add(KnowledgeDocument.builder().id("b1").content("Blog post about return policy").build());

        KnowledgeSearchTool tool = toolWithSources(faq, blog);
        ToolResult result = tool.execute(input(Map.of(
                "query", "return policy",
                "sources", List.of("faq")
        )));

        assertThat(result.isSuccess()).isTrue();
        String text = result.getData().toString();
        assertThat(text).contains("[faq]").doesNotContain("[blog]");
    }

    @Test
    void execute_respectsMaxResults() {
        InMemoryKnowledgeSource source = new InMemoryKnowledgeSource("s", "d");
        for (int i = 0; i < 10; i++) {
            source.add(KnowledgeDocument.builder().id("doc" + i).content("keyword match " + i).build());
        }

        ToolResult result = toolWithSources(source).execute(input(Map.of(
                "query", "keyword",
                "max_results", 3
        )));

        assertThat(result.isSuccess()).isTrue();
        // Count separators to verify result count
        String text = result.getData().toString();
        long count = text.lines().filter("---"::equals).count();
        assertThat(count).isLessThanOrEqualTo(3);
    }

    @Test
    void getName_returnsKnowledgeSearch() {
        assertThat(toolWithSources().getName()).isEqualTo("knowledge_search");
    }

    @Test
    void isRisky_returnsFalse() {
        assertThat(toolWithSources().isRisky()).isFalse();
    }

    @Test
    void getDescription_includesSourceNames() {
        InMemoryKnowledgeSource s = new InMemoryKnowledgeSource("product_catalog", "Products");
        assertThat(toolWithSources(s).getDescription()).contains("product_catalog");
    }
}
