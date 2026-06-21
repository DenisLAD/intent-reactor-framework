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

class ApplyPatchToolTest {

    private final ApplyPatchTool tool = new ApplyPatchTool();

    @TempDir
    Path tempDir;

    private ToolInput input(String patch) {
        return new ToolInput(Map.of("patch", patch), "test-session");
    }

    private Path file(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // ---- ADD ----

    @Test
    void addsNewFile() throws IOException {
        Path newFile = tempDir.resolve("new.txt");
        String patch =
                "--- /dev/null\n" +
                        "+++ " + newFile + "\n" +
                        "@@ -0,0 +1,2 @@\n" +
                        "+line one\n" +
                        "+line two\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("A");
        assertThat(Files.readString(newFile)).contains("line one");
        assertThat(Files.readString(newFile)).contains("line two");
    }

    @Test
    void createsParentDirectoriesForNewFile() throws IOException {
        Path newFile = tempDir.resolve("subdir/deep/new.txt");
        String patch =
                "--- /dev/null\n" +
                        "+++ " + newFile + "\n" +
                        "@@ -0,0 +1,1 @@\n" +
                        "+content\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(newFile)).isTrue();
    }

    // ---- DELETE ----

    @Test
    void deletesExistingFile() throws IOException {
        Path existing = file("to-delete.txt", "goodbye");
        String patch =
                "--- " + existing + "\n" +
                        "+++ /dev/null\n" +
                        "@@ -1,1 +0,0 @@\n" +
                        "-goodbye\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("D");
        assertThat(Files.exists(existing)).isFalse();
    }

    // ---- UPDATE ----

    @Test
    void modifiesExistingFile() throws IOException {
        Path existing = file("modify.txt", "line1\nline2\nline3");
        String patch =
                "--- " + existing + "\n" +
                        "+++ " + existing + "\n" +
                        "@@ -1,3 +1,3 @@\n" +
                        " line1\n" +
                        "-line2\n" +
                        "+modified\n" +
                        " line3\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).contains("M");
        String content = Files.readString(existing, StandardCharsets.UTF_8);
        assertThat(content).contains("line1");
        assertThat(content).contains("modified");
        assertThat(content).contains("line3");
        assertThat(content).doesNotContain("line2");
    }

    // ---- multi-file ----

    @Test
    void appliesMultiFilePatch() throws IOException {
        Path f1 = file("f1.txt", "original");
        Path f2 = tempDir.resolve("f2.txt");
        String patch =
                "--- " + f1 + "\n" +
                        "+++ " + f1 + "\n" +
                        "@@ -1,1 +1,1 @@\n" +
                        "-original\n" +
                        "+updated\n" +
                        "--- /dev/null\n" +
                        "+++ " + f2 + "\n" +
                        "@@ -0,0 +1,1 @@\n" +
                        "+brand new\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(f1)).contains("updated");
        assertThat(Files.readString(f2)).contains("brand new");
    }

    @Test
    void summaryContainsAMDPrefixes() throws IOException {
        Path modFile = file("mod.txt", "old");
        Path delFile = file("del.txt", "bye");
        Path addFile = tempDir.resolve("add.txt");

        String patch =
                "--- " + modFile + "\n" +
                        "+++ " + modFile + "\n" +
                        "@@ -1,1 +1,1 @@\n" +
                        "-old\n" +
                        "+new\n" +
                        "--- " + delFile + "\n" +
                        "+++ /dev/null\n" +
                        "@@ -1,1 +0,0 @@\n" +
                        "-bye\n" +
                        "--- /dev/null\n" +
                        "+++ " + addFile + "\n" +
                        "@@ -0,0 +1,1 @@\n" +
                        "+added\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("M");
        assertThat(out).contains("D");
        assertThat(out).contains("A");
    }

    // ---- error cases ----

    @Test
    void errorOnMissingPatch() {
        ToolResult result = tool.execute(new ToolInput(Map.of(), "s"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("patch");
    }

    @Test
    void errorOnBlankPatch() {
        ToolResult result = tool.execute(input("   "));
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void errorWhenUpdateTargetNotFound() {
        Path ghost = tempDir.resolve("ghost.txt");
        String patch =
                "--- " + ghost + "\n" +
                        "+++ " + ghost + "\n" +
                        "@@ -1,1 +1,1 @@\n" +
                        "-old\n" +
                        "+new\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("ghost");
    }

    // ---- Windows CRLF ----

    @Test
    void handlesWindowsLineEndings() throws IOException {
        Path newFile = tempDir.resolve("crlf.txt");
        String patch =
                "--- /dev/null\r\n" +
                        "+++ " + newFile + "\r\n" +
                        "@@ -0,0 +1,1 @@\r\n" +
                        "+hello\r\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(newFile)).isTrue();
    }

    // ---- multi-hunk ----

    @Test
    void appliesMultipleHunks() throws IOException {
        Path f = file("multi.txt", "A\nB\nC\nD\nE");
        String patch =
                "--- " + f + "\n" +
                        "+++ " + f + "\n" +
                        "@@ -1,2 +1,2 @@\n" +
                        "-A\n" +
                        "+X\n" +
                        " B\n" +
                        "@@ -4,2 +4,2 @@\n" +
                        " D\n" +
                        "-E\n" +
                        "+Y\n";

        ToolResult result = tool.execute(input(patch));

        assertThat(result.isSuccess()).isTrue();
        String content = Files.readString(f, StandardCharsets.UTF_8);
        assertThat(content).contains("X");
        assertThat(content).contains("Y");
        assertThat(content).doesNotContain("\nA\n");
        assertThat(content).doesNotContain("E");
    }
}
