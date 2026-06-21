package com.intentreactor.tools;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebFetchToolTest {

    private final WebFetchTool tool = new WebFetchTool();

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput(params, "test-session");
    }

    // ---- URL validation ----

    @Test
    void rejectsMissingUrl() {
        ToolResult result = tool.execute(input(Map.of()));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("url");
    }

    @Test
    void rejectsBlankUrl() {
        ToolResult result = tool.execute(input(Map.of("url", "   ")));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("url");
    }

    @Test
    void rejectsFtpUrl() {
        ToolResult result = tool.execute(input(Map.of("url", "ftp://example.com/file.txt")));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("http");
    }

    @Test
    void rejectsBareHostname() {
        ToolResult result = tool.execute(input(Map.of("url", "example.com")));
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void rejectsJavascriptUrl() {
        ToolResult result = tool.execute(input(Map.of("url", "javascript:alert(1)")));
        assertThat(result.isSuccess()).isFalse();
    }

    // ---- Format validation ----

    @Test
    void rejectsUnknownFormat() {
        ToolResult result = tool.execute(input(Map.of("url", "https://example.com", "format", "pdf")));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("format");
    }

    // ---- HTML → Markdown conversion (no network) ----

    @Test
    void htmlToMarkdownConvertsHeadings() {
        String markdown = tool.htmlToMarkdown("<h1>Hello</h1><p>World</p>");
        assertThat(markdown).startsWith("# Hello");
        assertThat(markdown).contains("World");
    }

    @Test
    void htmlToMarkdownConvertsLinks() {
        String markdown = tool.htmlToMarkdown("<a href=\"https://example.com\">click me</a>");
        assertThat(markdown).contains("[click me](https://example.com)");
    }

    @Test
    void htmlToMarkdownStripsScriptsAndStyles() {
        String markdown = tool.htmlToMarkdown(
                "<script>alert('xss')</script><style>.x{}</style><p>clean</p>");
        assertThat(markdown).doesNotContain("alert");
        assertThat(markdown).doesNotContain(".x");
        assertThat(markdown).contains("clean");
    }

    @Test
    void htmlToMarkdownConvertsCodeBlocks() {
        String markdown = tool.htmlToMarkdown("<pre>code here</pre>");
        assertThat(markdown).contains("```");
        assertThat(markdown).contains("code here");
    }

    @Test
    void htmlToMarkdownDecodesEntities() {
        String markdown = tool.htmlToMarkdown("<p>Hello &amp; World &lt;test&gt;</p>");
        assertThat(markdown).contains("Hello & World <test>");
    }

    // ---- HTML → text extraction (no network) ----

    @Test
    void extractTextRemovesTags() {
        String text = tool.extractText("<p>Hello <b>World</b></p>");
        assertThat(text).contains("Hello");
        assertThat(text).contains("World");
        assertThat(text).doesNotContain("<");
        assertThat(text).doesNotContain(">");
    }

    @Test
    void extractTextSkipsScriptContents() {
        String text = tool.extractText("<script>malicious()</script><p>visible</p>");
        assertThat(text).doesNotContain("malicious");
        assertThat(text).contains("visible");
    }

    @Test
    void extractTextSkipsIframeContents() {
        String text = tool.extractText("<iframe src=\"x\">hidden</iframe><p>main</p>");
        assertThat(text).doesNotContain("hidden");
        assertThat(text).contains("main");
    }

    // ---- Schema ----

    @Test
    void schemaHasRequiredUrl() {
        Map<String, Object> schema = tool.getParameterSchema();
        @SuppressWarnings("unchecked")
        java.util.List<Object> required = (java.util.List<Object>) schema.get("required");
        assertThat(required).contains("url");
    }

    @Test
    void isNotRisky() {
        assertThat(tool.isRisky()).isFalse();
    }
}
