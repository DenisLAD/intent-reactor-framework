package com.intentreactor.tools;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WriteFileToolTest {

    private final WriteFileTool tool = new WriteFileTool();
    @TempDir
    Path tempDir;

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput(params, "test-session");
    }

    @Test
    void writesNewFile() throws IOException {
        Path file = tempDir.resolve("hello.txt");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "content", "line one\nline two"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("line one\nline two");
    }

    @Test
    void createsParentDirectories() throws IOException {
        Path file = tempDir.resolve("a/b/c/nested.txt");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "content", "nested"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void overwritesExistingFile() throws IOException {
        Path file = tempDir.resolve("overwrite.txt");
        Files.writeString(file, "old content");

        tool.execute(input(Map.of("file_path", file.toString(), "content", "new content")));

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("new content");
    }

    @Test
    void returnsLineCountInResult() {
        Path file = tempDir.resolve("lines.txt");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "content", "a\nb\nc"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("3");
    }

    @Test
    void errorOnMissingFilePath() {
        ToolResult result = tool.execute(input(Map.of("content", "hello")));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("file_path");
    }

    @Test
    void errorOnMissingContent() {
        Path file = tempDir.resolve("missing.txt");
        ToolResult result = tool.execute(input(Map.of("file_path", file.toString())));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("content");
    }

    @Test
    void errorOnBlankFilePath() {
        ToolResult result = tool.execute(input(Map.of("file_path", "  ", "content", "x")));
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void emptyContentWritesEmptyFile() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        ToolResult result = tool.execute(input(Map.of("file_path", file.toString(), "content", "")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEmpty();
    }
}
