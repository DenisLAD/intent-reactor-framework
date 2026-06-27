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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads the full text content of a file at the given path. Prefer {@link ReadFileTool}
 * for large files — this tool loads the entire file at once without pagination.
 */
@Component
public class FileContentExtractorTool implements Tool {

    @Override
    public String getName() {
        return "file_content_extractor";
    }

    @Override
    public String getDescription() {
        return "Reads a file and splits its content into numbered segments for analysis. " +
                "Parameter 'filePath' is the absolute path to the file. " +
                "Parameter 'segmentSize' (integer, default 300) is the number of words per segment.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "filePath", Map.of("type", "string", "description", "Absolute path to the file to read"),
                        "segmentSize", Map.of("type", "integer", "description", "Words per segment (default: 300)")
                ),
                "required", List.of("filePath")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String filePath = (String) input.getParameters().get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("Parameter 'filePath' is required");
        }

        Object segSizeParam = input.getParameters().get("segmentSize");
        int segmentSize = segSizeParam instanceof Number n ? n.intValue() : 300;
        if (segmentSize < 1) segmentSize = 300;

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("Not a file: " + filePath);
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return ToolResult.ok("File is empty: " + filePath);
            }

            String[] words = content.split("\\s+");
            int totalSegments = (int) Math.ceil((double) words.length / segmentSize);

            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(filePath).append("\n");
            sb.append("Total words: ").append(words.length)
                    .append(" | Segments: ").append(totalSegments).append("\n\n");

            for (int seg = 0; seg < totalSegments; seg++) {
                int wordStart = seg * segmentSize;
                int wordEnd = Math.min(wordStart + segmentSize, words.length);
                String segmentText = String.join(" ", Arrays.copyOfRange(words, wordStart, wordEnd));

                sb.append("=== Segment ").append(seg + 1).append("/").append(totalSegments)
                        .append(" (words ").append(wordStart + 1).append("-").append(wordEnd).append(") ===\n")
                        .append(segmentText).append("\n\n");
            }
            return ToolResult.ok(sb.toString().trim());
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    @Override
    public boolean isRisky() {
        return false;
    }
}
