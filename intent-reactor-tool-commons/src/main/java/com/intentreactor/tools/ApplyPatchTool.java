package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a standard unified diff patch to the file system.
 * Supports ADD (--- /dev/null), UPDATE, and DELETE (+++ /dev/null) operations.
 */
@Component
public class ApplyPatchTool implements Tool {

    private static String getString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    @Override
    public String getName() {
        return "apply_patch";
    }

    @Override
    public String getDescription() {
        return "Apply a unified diff patch to the file system. " +
                "Supports adding new files (--- /dev/null), modifying existing files, " +
                "and deleting files (+++ /dev/null). " +
                "Parameter 'patch': the unified diff text (standard git diff format).";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "patch", Map.of("type", "string",
                                "description", "Unified diff text in git diff format")
                ),
                "required", List.of("patch")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        Object raw = input.getParameters().get("patch");
        if (!(raw instanceof String patchText) || patchText.isBlank()) {
            return ToolResult.error("Parameter 'patch' is required and must be non-empty");
        }

        List<FilePatch> patches;
        try {
            patches = PatchParser.parse(patchText);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid patch: " + e.getMessage());
        }

        if (patches.isEmpty()) {
            return ToolResult.error("No file changes found in the patch");
        }

        StringBuilder summary = new StringBuilder("Applied patch:\n");
        for (FilePatch fp : patches) {
            try {
                String prefix = applyFilePatch(fp);
                summary.append("  ").append(prefix).append(" ").append(fp.targetPath()).append("\n");
            } catch (IOException e) {
                return ToolResult.error("Failed to apply patch to " + fp.targetPath() + ": " + e.getMessage());
            }
        }

        return ToolResult.ok(summary.toString().trim());
    }

    // -----------------------------------------------------------------------
    // Application logic
    // -----------------------------------------------------------------------

    @Override
    public boolean isRisky() {
        return true;
    }

    private String applyFilePatch(FilePatch fp) throws IOException {
        Path target = Paths.get(fp.targetPath());

        switch (fp.operation()) {
            case ADD -> {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                String content = collectAddedLines(fp.hunks());
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return "A";
            }
            case DELETE -> {
                Files.deleteIfExists(target);
                return "D";
            }
            case UPDATE -> {
                if (!Files.exists(target)) {
                    throw new IOException("File to update does not exist: " + target);
                }
                List<String> lines = new ArrayList<>(
                        Files.readAllLines(target, StandardCharsets.UTF_8));
                String updated = applyHunks(lines, fp.hunks());
                Files.writeString(target, updated, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING);
                return "M";
            }
        }
        return "?";
    }

    private String collectAddedLines(List<Hunk> hunks) {
        StringBuilder sb = new StringBuilder();
        for (Hunk h : hunks) {
            for (HunkLine hl : h.lines()) {
                if (hl.type() == '+') {
                    sb.append(hl.content()).append("\n");
                }
            }
        }
        // remove trailing newline if content ends with one to avoid double-newline
        String result = sb.toString();
        if (result.endsWith("\n") && !result.equals("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Domain records
    // -----------------------------------------------------------------------

    private String applyHunks(List<String> origLines, List<Hunk> hunks) {
        List<String> result = new ArrayList<>(origLines);
        int lineOffset = 0;

        for (Hunk hunk : hunks) {
            int pos = Math.max(0, hunk.origStart() - 1 + lineOffset);
            for (HunkLine hl : hunk.lines()) {
                switch (hl.type()) {
                    case ' ' -> pos++;
                    case '-' -> {
                        if (pos < result.size()) result.remove(pos);
                        lineOffset--;
                    }
                    case '+' -> {
                        result.add(pos, hl.content());
                        pos++;
                        lineOffset++;
                    }
                }
            }
        }

        return String.join("\n", result);
    }

    private enum PatchOperation {ADD, DELETE, UPDATE}

    private record FilePatch(PatchOperation operation, String targetPath, List<Hunk> hunks) {
    }

    private record Hunk(int origStart, int origCount, List<HunkLine> lines) {
    }

    // -----------------------------------------------------------------------
    // Parser
    // -----------------------------------------------------------------------

    private record HunkLine(char type, String content) {
    }

    private static final class PatchParser {

        private static final Pattern HUNK_HEADER = Pattern.compile(
                "@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

        static List<FilePatch> parse(String patchText) {
            String normalized = patchText.replace("\r\n", "\n").replace("\r", "\n");
            String[] lines = normalized.split("\n", -1);

            List<FilePatch> result = new ArrayList<>();
            // group lines by file diff block (split on "--- ")
            // We'll use a two-pointer approach: find "---"/"+++" pairs
            Map<Integer, Integer> fileBlocks = new LinkedHashMap<>();
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("--- ")) {
                    fileBlocks.put(i, i);
                }
            }

            int[] startPositions = fileBlocks.keySet().stream().mapToInt(Integer::intValue).toArray();
            for (int bi = 0; bi < startPositions.length; bi++) {
                int start = startPositions[bi];
                int end = bi + 1 < startPositions.length ? startPositions[bi + 1] : lines.length;
                FilePatch fp = parseFileBlock(lines, start, end);
                if (fp != null) result.add(fp);
            }
            return result;
        }

        private static FilePatch parseFileBlock(String[] lines, int start, int end) {
            if (start + 1 >= end) return null;

            String oldPath = extractPath(lines[start].substring(4));   // after "--- "
            if (start + 1 >= lines.length || !lines[start + 1].startsWith("+++ ")) return null;
            String newPath = extractPath(lines[start + 1].substring(4)); // after "+++ "

            PatchOperation op;
            String targetPath;
            if (isDevNull(oldPath)) {
                op = PatchOperation.ADD;
                targetPath = newPath;
            } else if (isDevNull(newPath)) {
                op = PatchOperation.DELETE;
                targetPath = oldPath;
            } else {
                op = PatchOperation.UPDATE;
                targetPath = newPath; // use new path as target
            }

            // parse hunks starting at line start+2
            List<Hunk> hunks = parseHunks(lines, start + 2, end);
            return new FilePatch(op, targetPath, hunks);
        }

        private static List<Hunk> parseHunks(String[] lines, int from, int end) {
            List<Hunk> hunks = new ArrayList<>();
            int i = from;
            while (i < end) {
                String line = lines[i];
                Matcher m = HUNK_HEADER.matcher(line);
                if (m.matches()) {
                    int origStart = Integer.parseInt(m.group(1));
                    int origCount = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
                    i++;

                    List<HunkLine> hunkLines = new ArrayList<>();
                    while (i < end && !lines[i].startsWith("@@") && !lines[i].startsWith("---")) {
                        String l = lines[i];
                        if (l.startsWith("+")) {
                            hunkLines.add(new HunkLine('+', l.substring(1)));
                        } else if (l.startsWith("-")) {
                            hunkLines.add(new HunkLine('-', l.substring(1)));
                        } else if (l.startsWith(" ")) {
                            hunkLines.add(new HunkLine(' ', l.substring(1)));
                        } else if (l.startsWith("\\")) {
                            // "\ No newline at end of file" — ignore
                        }
                        i++;
                    }
                    hunks.add(new Hunk(origStart, origCount, hunkLines));
                } else {
                    i++;
                }
            }
            return hunks;
        }

        private static String extractPath(String raw) {
            // Strip optional timestamp suffix (e.g. "\t2024-01-01 ...") and git a/b prefix
            String path = raw.trim();
            int tab = path.indexOf('\t');
            if (tab >= 0) path = path.substring(0, tab).trim();
            if (path.startsWith("a/") || path.startsWith("b/")) path = path.substring(2);
            // Strip surrounding quotes
            if (path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);
            return path;
        }

        private static boolean isDevNull(String path) {
            return "/dev/null".equals(path) || "nul".equalsIgnoreCase(path);
        }
    }
}
