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
import java.util.List;
import java.util.Map;

/**
 * Creates or overwrites a file at the given path, creating all necessary parent directories.
 * Marked {@code isRisky = true} so autonomous execution requires confirmation.
 */
@Component
public class WriteFileTool implements Tool {

    private static String getString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "Write content to a file, creating the file and any missing parent directories. " +
                "Overwrites the file if it already exists. " +
                "Parameter 'file_path': absolute or relative path to the target file. " +
                "Parameter 'content': full text content to write.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string",
                                "description", "Absolute or relative path to the file to write"),
                        "content", Map.of("type", "string",
                                "description", "Full text content to write to the file")
                ),
                "required", List.of("file_path", "content")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        Map<String, Object> params = input.getParameters();
        String filePath = getString(params, "file_path");
        String content = getString(params, "content");

        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("Parameter 'file_path' is required");
        }
        if (content == null) {
            return ToolResult.error("Parameter 'content' is required");
        }

        try {
            Path path = Paths.get(filePath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            long lineCount = content.lines().count();
            return ToolResult.ok("Written " + lineCount + " line(s) to " + path.toAbsolutePath());
        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    @Override
    public boolean isRisky() {
        return true;
    }
}
