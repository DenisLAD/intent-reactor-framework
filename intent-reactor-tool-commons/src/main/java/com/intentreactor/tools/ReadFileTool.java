package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class ReadFileTool implements Tool {

    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LINES = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int BINARY_PROBE_BYTES = 4096;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "zip", "jar", "class", "exe", "dll", "so", "dylib",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp",
            "mp3", "mp4", "wav", "avi", "mkv",
            "bin", "dat", "db", "sqlite", "tar", "gz", "7z", "rar"
    );

    private static String getString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    private static int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number n ? n.intValue() : defaultVal;
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read a file and return its content as numbered lines. " +
                "If the path is a directory, list its entries. " +
                "Parameter 'file_path': path to the file or directory to read. " +
                "Parameter 'offset': 1-based line number to start from (default: 1). " +
                "Parameter 'limit': maximum number of lines to return (default: 2000, max: 2000). " +
                "Use offset+limit for pagination of large files.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string",
                                "description", "Path to the file or directory to read"),
                        "offset", Map.of("type", "integer",
                                "description", "1-based line number to start reading from (default: 1)"),
                        "limit", Map.of("type", "integer",
                                "description", "Maximum number of lines to return (default: 2000, max: 2000)")
                ),
                "required", List.of("file_path")
        );
    }

    // --- directory ---

    @Override
    public ToolResult execute(ToolInput input) {
        Map<String, Object> params = input.getParameters();
        String filePath = getString(params, "file_path");
        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("Parameter 'file_path' is required");
        }

        int offset = Math.max(1, getInt(params, "offset", 1));
        int limit = Math.min(MAX_LINES, Math.max(1, getInt(params, "limit", DEFAULT_LIMIT)));

        try {
            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                return ToolResult.error("Path not found: " + filePath);
            }
            if (Files.isDirectory(path)) {
                return listDirectory(path);
            }
            if (isBinaryByExtension(path) || isBinaryByContent(path)) {
                return ToolResult.error("Cannot read binary file: " + filePath);
            }

            return readTextFile(path, offset, limit);

        } catch (IOException e) {
            return ToolResult.error("Failed to read: " + e.getMessage());
        }
    }

    // --- text file ---

    @Override
    public boolean isRisky() {
        return false;
    }

    // --- binary detection ---

    private ToolResult listDirectory(Path dir) throws IOException {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Directory: ").append(dir.toAbsolutePath()).append("\n");
        for (Path entry : entries) {
            sb.append(entry.getFileName());
            if (Files.isDirectory(entry)) sb.append("/");
            sb.append("\n");
        }
        return ToolResult.ok(sb.toString().trim());
    }

    private ToolResult readTextFile(Path path, int offset, int limit) throws IOException {
        List<String> allLines = new ArrayList<>();
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(allLines::add);
        } catch (UncheckedIOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }

        int totalLines = allLines.size();
        if (totalLines == 0) {
            return ToolResult.ok("(Empty file - 0 lines)");
        }
        if (offset > totalLines) {
            return ToolResult.ok("(Offset " + offset + " exceeds file length of " + totalLines + " lines)");
        }

        int fromIdx = offset - 1;           // inclusive, 0-based
        int toIdx = Math.min(fromIdx + limit, totalLines);  // exclusive

        StringBuilder sb = new StringBuilder();
        for (int i = fromIdx; i < toIdx; i++) {
            String line = allLines.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "...[truncated]";
            }
            sb.append(i + 1).append(": ").append(line).append("\n");
        }

        int lastShown = toIdx; // 1-based last line shown
        if (lastShown < totalLines) {
            sb.append("[Showing lines ").append(offset).append("-").append(lastShown)
                    .append(" of ").append(totalLines)
                    .append(". Use offset=").append(lastShown + 1).append(" to read further.]");
        } else {
            sb.append("(End of file — total ").append(totalLines).append(" lines)");
        }

        return ToolResult.ok(sb.toString());
    }

    // --- param helpers ---

    private boolean isBinaryByExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private boolean isBinaryByContent(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[BINARY_PROBE_BYTES];
            int read = in.read(buf, 0, buf.length);
            for (int i = 0; i < read; i++) {
                if (buf[i] == 0) return true;
            }
        }
        return false;
    }
}
