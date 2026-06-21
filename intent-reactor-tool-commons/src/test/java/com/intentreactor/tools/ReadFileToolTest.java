package com.intentreactor.tools;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadFileToolTest {

    private final ReadFileTool tool = new ReadFileTool();

    @TempDir
    Path tempDir;

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput(params, "test-session");
    }

    @Test
    void readsFileWithLineNumbers() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "alpha\nbeta\ngamma");

        ToolResult result = tool.execute(input(Map.of("file_path", file.toString())));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("1: alpha");
        assertThat(out).contains("2: beta");
        assertThat(out).contains("3: gamma");
    }

    @Test
    void respectsOffsetAndLimit() throws IOException {
        Path file = tempDir.resolve("paged.txt");
        Files.writeString(file, "L1\nL2\nL3\nL4\nL5");

        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "offset", 3,
                "limit", 2
        )));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("3: L3");
        assertThat(out).contains("4: L4");
        assertThat(out).doesNotContain("1: L1");
        assertThat(out).doesNotContain("5: L5");
    }

    @Test
    void defaultLimitDoesNotExceed2000() throws IOException {
        Path file = tempDir.resolve("big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 2001; i++) {
            sb.append("line ").append(i).append("\n");
        }
        Files.writeString(file, sb.toString());

        ToolResult result = tool.execute(input(Map.of("file_path", file.toString())));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("1: line 1");
        assertThat(out).contains("2000: line 2000");
        assertThat(out).doesNotContain("2001: line 2001");
        assertThat(out).contains("offset=2001");
    }

    @Test
    void truncatesLongLines() throws IOException {
        Path file = tempDir.resolve("long.txt");
        String longLine = "X".repeat(3000);
        Files.writeString(file, longLine);

        ToolResult result = tool.execute(input(Map.of("file_path", file.toString())));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("...[truncated]");
        assertThat(out).doesNotContain("X".repeat(2001));
    }

    @Test
    void appendsPaginationHintWhenMoreLinesExist() throws IOException {
        Path file = tempDir.resolve("hint.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) sb.append("line ").append(i).append("\n");
        Files.writeString(file, sb.toString());

        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "limit", 5
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("offset=6");
    }

    @Test
    void listDirectory() throws IOException {
        Path sub = tempDir.resolve("sub");
        Files.createDirectory(sub);
        Files.writeString(sub.resolve("a.txt"), "a");
        Files.createDirectory(sub.resolve("child"));

        ToolResult result = tool.execute(input(Map.of("file_path", sub.toString())));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("a.txt");
        assertThat(out).contains("child/");
    }

    @Test
    void detectsBinaryByExtension() throws IOException {
        Path file = tempDir.resolve("archive.zip");
        Files.writeString(file, "not really a zip");

        ToolResult result = tool.execute(input(Map.of("file_path", file.toString())));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("binary");
    }

    @Test
    void detectsBinaryByNullByte() throws IOException {
        Path file = tempDir.resolve("binary.bin");
        byte[] bytes = {72, 101, 108, 108, 111, 0, 87, 111, 114, 108, 100}; // contains null byte
        Files.write(file, bytes);

        ToolResult result = tool.execute(input(Map.of("file_path", file.toString())));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("binary");
    }

    @Test
    void errorOnNonExistentPath() {
        ToolResult result = tool.execute(input(Map.of("file_path", "/nonexistent/path/file.txt")));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void errorOnMissingFilePathParam() {
        ToolResult result = tool.execute(input(Map.of()));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("file_path");
    }

    @Test
    void emptyFileReturnsOk() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        ToolResult result = tool.execute(input(Map.of("file_path", file.toString())));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).containsIgnoringCase("0 lines");
    }

    @Test
    void offsetBeyondEofReturnsOk() throws IOException {
        Path file = tempDir.resolve("short.txt");
        Files.writeString(file, "only one line");

        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "offset", 100
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("100");
    }

    @Test
    void endOfFileMessageWhenFullyRead() throws IOException {
        Path file = tempDir.resolve("full.txt");
        Files.writeString(file, "a\nb\nc");

        ToolResult result = tool.execute(input(Map.of("file_path", file.toString())));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).containsIgnoringCase("end of file");
    }

    @Test
    void returnsCorrectLineNumbersWithOffset() throws IOException {
        Path file = tempDir.resolve("numbered.txt");
        List<String> lines = List.of("A", "B", "C", "D", "E");
        Files.write(file, lines, StandardCharsets.UTF_8);

        ToolResult result = tool.execute(input(Map.of(
                "file_path", file.toString(),
                "offset", 3,
                "limit", 2
        )));

        String out = result.getData().toString();
        assertThat(out).contains("3: C");
        assertThat(out).contains("4: D");
    }
}
