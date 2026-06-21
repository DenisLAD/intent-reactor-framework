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

class GlobToolTest {

    private final GlobTool tool = new GlobTool();

    @TempDir
    Path tempDir;

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput(params, "test-session");
    }

    @Test
    void findsMatchingJavaFiles() throws IOException {
        Files.writeString(tempDir.resolve("A.java"), "class A {}");
        Files.writeString(tempDir.resolve("B.java"), "class B {}");
        Files.writeString(tempDir.resolve("config.xml"), "<x/>");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "**/*.java",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("A.java");
        assertThat(out).contains("B.java");
        assertThat(out).doesNotContain("config.xml");
    }

    @Test
    void returnsNoFilesFoundWhenNoMatch() throws IOException {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "**/*.java",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).isEqualTo("No files found");
    }

    @Test
    void truncatesAt100Results() throws IOException {
        for (int i = 0; i < 105; i++) {
            Files.writeString(tempDir.resolve("f" + i + ".txt"), "x");
        }

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "**/*.txt",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        long lineCount = out.lines().filter(l -> l.endsWith(".txt")).count();
        assertThat(lineCount).isEqualTo(100);
        assertThat(out).containsIgnoringCase("truncated");
    }

    @Test
    void findsFilesInSubdirectories() throws IOException {
        Path sub = tempDir.resolve("sub");
        Files.createDirectory(sub);
        Files.writeString(sub.resolve("deep.java"), "class D {}");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "**/*.java",
                "path", tempDir.toString()
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("deep.java");
    }

    @Test
    void errorWhenPathIsFile() throws IOException {
        Path file = tempDir.resolve("f.txt");
        Files.writeString(file, "x");

        ToolResult result = tool.execute(input(Map.of(
                "pattern", "*.txt",
                "path", file.toString()
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("directory");
    }

    @Test
    void errorWhenPathDoesNotExist() {
        ToolResult result = tool.execute(input(Map.of(
                "pattern", "*.txt",
                "path", tempDir.resolve("no-such-dir").toString()
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void errorOnMissingPattern() {
        ToolResult result = tool.execute(input(Map.of("path", tempDir.toString())));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("pattern");
    }

    @Test
    void worksWithoutExplicitPath() {
        // just verifies no crash when path is omitted (uses '.')
        ToolResult result = tool.execute(input(Map.of("pattern", "**/*.java")));
        assertThat(result).isNotNull();
        // no assertion on content since we don't control the CWD in tests
    }
}
