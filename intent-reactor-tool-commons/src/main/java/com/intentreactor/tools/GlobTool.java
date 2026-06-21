package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 100;

    private static String getString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    @Override
    public String getName() {
        return "glob";
    }

    @Override
    public String getDescription() {
        return "Find files matching a glob pattern in a directory tree. " +
                "Returns up to 100 matching absolute paths. " +
                "Parameter 'pattern': glob pattern, e.g. '**/*.java', 'src/**/*.xml'. " +
                "Parameter 'path': directory to search in (default: current directory '.').";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string",
                                "description", "Glob pattern to match files, e.g. '**/*.java'"),
                        "path", Map.of("type", "string",
                                "description", "Directory to search (default: '.')")
                ),
                "required", List.of("pattern")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        Map<String, Object> params = input.getParameters();
        String pattern = getString(params, "pattern");
        String pathParam = getString(params, "path");

        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("Parameter 'pattern' is required");
        }

        Path base = Paths.get(pathParam != null ? pathParam : ".").toAbsolutePath().normalize();

        if (!Files.exists(base)) {
            return ToolResult.error("Directory not found: " + base);
        }
        if (!Files.isDirectory(base)) {
            return ToolResult.error("Path must be a directory, not a file: " + base);
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (Exception e) {
            return ToolResult.error("Invalid glob pattern: " + e.getMessage());
        }

        // Fallback matcher: if pattern starts with "**/" and a root-level file has one component,
        // Java's PathMatcher won't match it because there's no separator before the filename.
        PathMatcher rootMatcher = pattern.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                : null;

        List<Path> results = new ArrayList<>();
        boolean truncated = false;

        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                Path relative = base.relativize(p);
                boolean matched = matcher.matches(relative)
                        || (rootMatcher != null && relative.getNameCount() == 1
                        && rootMatcher.matches(relative));
                if (matched) {
                    results.add(p.toAbsolutePath().normalize());
                    if (results.size() > MAX_RESULTS) {
                        truncated = true;
                        break;
                    }
                }
            }
        } catch (IOException e) {
            return ToolResult.error("Error walking directory: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return ToolResult.ok("No files found");
        }

        results.sort(null);
        if (truncated) results = results.subList(0, MAX_RESULTS);

        StringBuilder sb = new StringBuilder();
        for (Path p : results) {
            sb.append(p).append("\n");
        }
        if (truncated) {
            sb.append("\n[Results truncated: showing first ")
                    .append(MAX_RESULTS)
                    .append(". Use a more specific pattern to narrow results.]");
        }

        return ToolResult.ok(sb.toString().trim());
    }

    @Override
    public boolean isRisky() {
        return false;
    }
}
