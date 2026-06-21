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

class EditFileToolTest {

    private final EditFileTool tool = new EditFileTool();

    @TempDir
    Path tempDir;

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput(params, "test-session");
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ---- exact replacer ----

    @Test
    void exactReplacement() throws IOException {
        Path file = writeFile("a.txt", "Hello World");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "World",
                "new_string", "Java"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("Hello Java");
    }

    @Test
    void exactReplaceAll() throws IOException {
        Path file = writeFile("b.txt", "foo bar foo baz foo");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "foo",
                "new_string", "qux",
                "replace_all", true
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("qux bar qux baz qux");
    }

    @Test
    void multipleMatchesReturnsError() throws IOException {
        Path file = writeFile("c.txt", "abc abc abc");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "abc",
                "new_string", "xyz"
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("Multiple matches");
    }

    // ---- line-trimmed replacer ----

    @Test
    void lineTrimmedReplacerMatchesIndentedBlock() throws IOException {
        Path file = writeFile("d.txt", "    public void foo() {\n    }\n");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "public void foo() {\n}",  // no leading spaces
                "new_string", "public void bar() {\n}"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).contains("bar");
    }

    // ---- whitespace-normalized replacer ----

    @Test
    void whitespaceNormalizedReplacerMatchesExtraSpaces() throws IOException {
        Path file = writeFile("e.txt", "The  quick   brown fox");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "quick brown fox",
                "new_string", "lazy dog"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).contains("lazy dog");
    }

    // ---- create-file mode ----

    @Test
    void createsFileWhenOldStringEmptyAndFileNotExists() {
        Path file = tempDir.resolve("new.txt");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "",
                "new_string", "brand new content"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void errorWhenOldStringEmptyAndFileExists() throws IOException {
        Path file = writeFile("exists.txt", "existing");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "",
                "new_string", "new"
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("write_file");
    }

    // ---- error cases ----

    @Test
    void errorWhenStringNotFound() throws IOException {
        Path file = writeFile("f.txt", "some text");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "no such text",
                "new_string", "x"
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void errorOnMissingFilePath() {
        ToolResult result = tool.execute(input(Map.of(
                "old_string", "x",
                "new_string", "y"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("file_path");
    }

    @Test
    void errorOnMissingOldString() throws IOException {
        Path file = writeFile("g.txt", "text");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "new_string", "y"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("old_string");
    }

    @Test
    void errorOnMissingNewString() throws IOException {
        Path file = writeFile("h.txt", "text");
        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "text"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("new_string");
    }

    @Test
    void errorOnFileNotFound() {
        ToolResult result = tool.execute(input(Map.of(
                "file_path", tempDir.resolve("ghost.txt").toString(),
                "old_string", "x",
                "new_string", "y"
        )));
        assertThat(result.isSuccess()).isFalse();
    }

    // ---- correctness: surrounding text preserved ----

    @Test
    void preservesSurroundingText() throws IOException {
        Path file = writeFile("i.txt", "before TARGET after");
        tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "TARGET",
                "new_string", "REPLACEMENT"
        )));
        assertThat(Files.readString(file)).isEqualTo("before REPLACEMENT after");
    }

    @Test
    void multilineReplacement() throws IOException {
        Path file = writeFile("j.txt", "line1\nTARGET\nline3");
        tool.execute(input(Map.of(
                "file_path", file.toString(),
                "old_string", "TARGET",
                "new_string", "REPLACED"
        )));
        assertThat(Files.readString(file)).isEqualTo("line1\nREPLACED\nline3");
    }
}
