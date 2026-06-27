package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recursively scans a directory for {@code .md} files and returns their relative paths.
 */
@Component
public class MarkdownFileScannerTool implements Tool {

    @Override
    public String getName() {
        return "markdown_file_scanner";
    }

    @Override
    public String getDescription() {
        return "Scans a directory for Markdown (.md) files and returns their paths. " +
                "Parameter 'directory' is the path to scan. " +
                "Parameter 'recursive' (boolean, default true) controls whether to scan subdirectories.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "directory", Map.of("type", "string", "description", "Directory path to scan for .md files"),
                        "recursive", Map.of("type", "boolean", "description", "Scan subdirectories (default: true)")
                ),
                "required", List.of("directory")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String directory = (String) input.getParameters().get("directory");
        if (directory == null || directory.isBlank()) {
            return ToolResult.error("Parameter 'directory' is required");
        }
        Object recursiveParam = input.getParameters().get("recursive");
        boolean recursive = !Boolean.FALSE.equals(recursiveParam);

        Path dir = Paths.get(directory);
        if (!Files.exists(dir)) {
            return ToolResult.error("Directory does not exist: " + directory);
        }
        if (!Files.isDirectory(dir)) {
            return ToolResult.error("Not a directory: " + directory);
        }

        try {
            int maxDepth = recursive ? Integer.MAX_VALUE : 1;
            List<String> files = Files.walk(dir, maxDepth)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .sorted()
                    .collect(Collectors.toList());

            if (files.isEmpty()) {
                return ToolResult.ok("No markdown files found in: " + directory);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(files.size()).append(" markdown file(s) in ").append(directory).append(":\n");
            for (int i = 0; i < files.size(); i++) {
                sb.append(i + 1).append(". ").append(files.get(i)).append("\n");
            }
            return ToolResult.ok(sb.toString().trim());
        } catch (IOException e) {
            return ToolResult.error("Failed to scan directory: " + e.getMessage());
        }
    }

    @Override
    public boolean isRisky() {
        return false;
    }
}
