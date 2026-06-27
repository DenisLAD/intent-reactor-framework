package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Searches file contents with a regular expression; supports an optional {@code include} glob
 * to limit the search scope. Returns up to 100 matches with file path and line number.
 */
@Component
public class GrepTool implements Tool {

    private static final int MAX_MATCHES = 100;
    private static final int MAX_LINE_CHARS = 2000;

    private static String getString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public String getDescription() {
        return "Search file contents using a regular expression. " +
                "Returns matching lines grouped by file, up to 100 matches total. " +
                "Parameter 'pattern': regular expression to search for. " +
                "Parameter 'path': directory or file to search in (default: '.'). " +
                "Parameter 'include': glob pattern to filter file names, e.g. '*.java'.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string",
                                "description", "Regular expression to search for"),
                        "path", Map.of("type", "string",
                                "description", "Directory or file to search in (default: '.')"),
                        "include", Map.of("type", "string",
                                "description", "Glob filter for file names, e.g. '*.java'")
                ),
                "required", List.of("pattern")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        Map<String, Object> params = input.getParameters();
        String patternStr = getString(params, "pattern");
        String pathParam = getString(params, "path");
        String include = getString(params, "include");

        if (patternStr == null || patternStr.isBlank()) {
            return ToolResult.error("Parameter 'pattern' is required");
        }

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Invalid regex: " + e.getMessage());
        }

        Path base = Paths.get(pathParam != null ? pathParam : ".").toAbsolutePath().normalize();
        if (!Files.exists(base)) {
            return ToolResult.error("Path not found: " + base);
        }

        PathMatcher includeMatcher = null;
        if (include != null && !include.isBlank()) {
            includeMatcher = FileSystems.getDefault().getPathMatcher("glob:" + include);
        }

        List<String> outputLines = new ArrayList<>();
        int[] totalMatches = {0};
        boolean[] truncated = {false};

        try (Stream<Path> walk = Files.walk(base)) {
            outer:
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (includeMatcher != null && !includeMatcher.matches(p.getFileName())) continue;

                List<String> fileMatches = scanFile(p, regex, MAX_MATCHES - totalMatches[0]);
                if (!fileMatches.isEmpty()) {
                    outputLines.add("");
                    outputLines.add(p.toAbsolutePath().normalize() + ":");
                    outputLines.addAll(fileMatches);
                    totalMatches[0] += fileMatches.size();
                    if (totalMatches[0] >= MAX_MATCHES) {
                        truncated[0] = true;
                        break outer;
                    }
                }
            }
        } catch (IOException e) {
            return ToolResult.error("Error walking directory: " + e.getMessage());
        }

        if (outputLines.isEmpty()) {
            return ToolResult.ok("No files found");
        }

        StringBuilder sb = new StringBuilder();
        for (String line : outputLines) {
            sb.append(line).append("\n");
        }
        if (truncated[0]) {
            sb.append("\n[Results truncated at ").append(MAX_MATCHES)
                    .append(" matches. Narrow your search or use a more specific path.]");
        }

        return ToolResult.ok(sb.toString().trim());
    }

    @Override
    public boolean isRisky() {
        return false;
    }

    private List<String> scanFile(Path file, Pattern regex, int remaining) {
        List<String> matches = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return matches; // skip non-UTF-8 files
        } catch (IOException | UncheckedIOException e) {
            return matches; // skip unreadable files
        }

        Matcher matcher = regex.matcher("");
        for (int i = 0; i < lines.size() && matches.size() < remaining; i++) {
            String line = lines.get(i);
            matcher.reset(line);
            if (matcher.find()) {
                String display = line.length() > MAX_LINE_CHARS
                        ? line.substring(0, MAX_LINE_CHARS) + "..."
                        : line;
                matches.add("  Line " + (i + 1) + ": " + display);
            }
        }
        return matches;
    }
}
