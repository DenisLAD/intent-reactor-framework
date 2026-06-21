package com.intentreactor.tools;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrepToolTest {

    private final GrepTool tool = new GrepTool();

    @TempDir
    Path tempDir;

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput(params, "test-session");
    }

    @Test
    void findsMatchingLines() throws IOException {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello world\nno match\nhello again");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "hello",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("Line 1:");
        assertThat(out).contains("hello world");
        assertThat(out).contains("Line 3:");
        assertThat(out).contains("hello again");
        assertThat(out).doesNotContain("no match");
    }

    @Test
    void groupsResultsByFile() throws IOException {
        Files.writeString(tempDir.resolve("f1.txt"), "match here");
        Files.writeString(tempDir.resolve("f2.txt"), "another match");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "match",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("f1.txt:");
        assertThat(out).contains("f2.txt:");
    }

    @Test
    void invalidRegexReturnsError() {
        ToolResult result = tool.execute(input(Map.of(
                "pattern", "[invalid",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("Invalid regex");
    }

    @Test
    void includeFilterApplied() throws IOException {
        Files.writeString(tempDir.resolve("a.java"), "target");
        Files.writeString(tempDir.resolve("b.txt"), "target");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "target",
                "path", tempDir.toString(),
                "include", "*.java"
        )));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("a.java");
        assertThat(out).doesNotContain("b.txt");
    }

    @Test
    void noMatchReturnsNoFilesFound() throws IOException {
        Files.writeString(tempDir.resolve("empty.txt"), "nothing interesting");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "zzznomatch",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).isEqualTo("No files found");
    }

    @Test
    void truncatesAt100Matches() throws IOException {
        // Create a file with 110 matching lines
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 110; i++) sb.append("match line ").append(i).append("\n");
        Files.writeString(tempDir.resolve("many.txt"), sb.toString());

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "match",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        long matchLines = out.lines().filter(l -> l.trim().startsWith("Line")).count();
        assertThat(matchLines).isLessThanOrEqualTo(100);
        assertThat(out).containsIgnoringCase("truncated");
    }

    @Test
    void truncatesLongMatchingLines() throws IOException {
        String longLine = "match " + "X".repeat(3000);
        Files.writeString(tempDir.resolve("long.txt"), longLine);

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "match",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("...");
        // no line longer than 2020 chars (2000 + "...") in output
        out.lines().forEach(line -> assertThat(line.length()).isLessThan(2100));
    }

    @Test
    void skipsNonUtf8Files() throws IOException {
        // Write a file with ISO-8859-1 content (non-UTF-8 high bytes)
        byte[] latin1Bytes = {(byte) 0xE9, (byte) 0xE8, (byte) 0xEA, '\n'}; // é, è, ê in Latin-1
        Files.write(tempDir.resolve("latin1.txt"), latin1Bytes);
        Files.writeString(tempDir.resolve("utf8.txt"), "target found here");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "target",
                "path", tempDir.toString()
        )));

        // Should still find utf8.txt without crashing on latin1.txt
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("utf8.txt");
    }

    @Test
    void errorOnMissingPattern() {
        ToolResult result = tool.execute(input(Map.of("path", tempDir.toString())));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("pattern");
    }

    @Test
    void worksWithDefaultPath() {
        // Just verifies no crash with missing path parameter
        ToolResult result = tool.execute(input(Map.of("pattern", "java")));
        assertThat(result).isNotNull();
    }

    @Test
    void supportsRegexSpecialChars() throws IOException {
        Files.writeString(tempDir.resolve("r.txt"), "price: $9.99\nfree!");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "\\$\\d+\\.\\d+",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("$9.99");
    }
}
