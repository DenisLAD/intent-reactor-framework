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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replaces old_string with new_string in a file using a chain of matching strategies.
 * When old_string is empty and the file does not exist, creates a new file.
 */
@Component
public class EditFileTool implements Tool {

    private static String getString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    private static boolean getBoolean(Map<String, Object> params, String key, boolean defaultVal) {
        Object v = params.get(key);
        return v instanceof Boolean b ? b : defaultVal;
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Edit a file by replacing a specific string with a new string. " +
                "Uses a chain of matching strategies (exact → line-trimmed → whitespace-normalized) to locate the text. " +
                "If 'old_string' is empty and the file does not exist, creates a new file with 'new_string'. " +
                "Parameter 'file_path': path to the file. " +
                "Parameter 'old_string': text to find and replace (required). " +
                "Parameter 'new_string': replacement text. " +
                "Parameter 'replace_all': replace all occurrences (default: false).";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "Path to the file to edit"),
                        "old_string", Map.of("type", "string", "description", "Text to find and replace"),
                        "new_string", Map.of("type", "string", "description", "Replacement text"),
                        "replace_all", Map.of("type", "boolean", "description", "Replace all occurrences (default: false)")
                ),
                "required", List.of("file_path", "old_string", "new_string")
        );
    }

    // -----------------------------------------------------------------------
    // Empty-old-string: create-or-error
    // -----------------------------------------------------------------------

    @Override
    public ToolResult execute(ToolInput input) {
        Map<String, Object> params = input.getParameters();
        String filePath = getString(params, "file_path");
        String oldString = getString(params, "old_string");
        String newString = getString(params, "new_string");
        boolean replaceAll = getBoolean(params, "replace_all", false);

        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("Parameter 'file_path' is required");
        }
        if (oldString == null) {
            return ToolResult.error("Parameter 'old_string' is required");
        }
        if (newString == null) {
            return ToolResult.error("Parameter 'new_string' is required");
        }

        try {
            Path path = Paths.get(filePath);

            if (oldString.isEmpty()) {
                return handleEmptyOldString(path, newString);
            }
            if (!Files.exists(path)) {
                return ToolResult.error("File not found: " + filePath);
            }
            if (Files.isDirectory(path)) {
                return ToolResult.error("Path is a directory, not a file: " + filePath);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            Optional<ReplaceResult> found = tryReplacers(content, oldString, newString, replaceAll);

            if (found.isEmpty()) {
                return ToolResult.error("String not found in file: " + filePath);
            }
            ReplaceResult rr = found.get();
            if (rr.multipleFound()) {
                return ToolResult.error(
                        "Multiple matches found. Provide more context in 'old_string' to identify a unique location.");
            }

            Files.writeString(path, rr.newContent(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return ToolResult.ok("Edited " + rr.count() + " occurrence(s) in " + path.toAbsolutePath());

        } catch (IOException e) {
            return ToolResult.error("Failed to edit file: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Replacer chain
    // -----------------------------------------------------------------------

    @Override
    public boolean isRisky() {
        return true;
    }

    // -----------------------------------------------------------------------
    // Result record
    // -----------------------------------------------------------------------

    private ToolResult handleEmptyOldString(Path path, String newString) throws IOException {
        if (Files.exists(path)) {
            return ToolResult.error(
                    "File already exists. Use 'write_file' to overwrite: " + path.toAbsolutePath());
        }
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, newString, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return ToolResult.ok("Created new file: " + path.toAbsolutePath());
    }

    // -----------------------------------------------------------------------
    // Strategy interface
    // -----------------------------------------------------------------------

    private Optional<ReplaceResult> tryReplacers(
            String content, String old, String newStr, boolean replaceAll) {

        for (Replacer r : List.of(new ExactReplacer(), new LineTrimmedReplacer(), new WhitespaceNormalizedReplacer())) {
            Optional<ReplaceResult> result = r.replace(content, old, newStr, replaceAll);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Strategy 1: Exact match
    // -----------------------------------------------------------------------

    private interface Replacer {
        Optional<ReplaceResult> replace(String content, String old, String newStr, boolean replaceAll);
    }

    // -----------------------------------------------------------------------
    // Strategy 2: Line-trimmed match
    // -----------------------------------------------------------------------

    private record ReplaceResult(String newContent, int count, boolean multipleFound) {
    }

    // -----------------------------------------------------------------------
    // Strategy 3: Whitespace-normalized match
    // -----------------------------------------------------------------------

    private final class ExactReplacer implements Replacer {
        @Override
        public Optional<ReplaceResult> replace(String content, String old, String newStr, boolean replaceAll) {
            int first = content.indexOf(old);
            if (first < 0) return Optional.empty();

            if (replaceAll) {
                int count = countOccurrences(content, old);
                return Optional.of(new ReplaceResult(content.replace(old, newStr), count, false));
            }
            int second = content.indexOf(old, first + old.length());
            if (second >= 0) {
                return Optional.of(new ReplaceResult(null, 0, true));
            }
            String replaced = content.substring(0, first) + newStr + content.substring(first + old.length());
            return Optional.of(new ReplaceResult(replaced, 1, false));
        }

        private int countOccurrences(String text, String sub) {
            int count = 0, idx = 0;
            while ((idx = text.indexOf(sub, idx)) >= 0) {
                count++;
                idx += sub.length();
            }
            return count;
        }
    }

    // -----------------------------------------------------------------------
    // Param helpers
    // -----------------------------------------------------------------------

    private final class LineTrimmedReplacer implements Replacer {
        @Override
        public Optional<ReplaceResult> replace(String content, String old, String newStr, boolean replaceAll) {
            String[] oldLines = old.split("\n", -1);
            String[] contentLines = content.split("\n", -1);
            int windowSize = oldLines.length;

            if (windowSize == 0 || windowSize > contentLines.length) return Optional.empty();

            String[] trimmedOld = trimAll(oldLines);
            String[] trimmedContent = trimAll(contentLines);

            // pre-compute line start offsets in original content
            int[] startOffsets = new int[contentLines.length];
            int pos = 0;
            for (int i = 0; i < contentLines.length; i++) {
                startOffsets[i] = pos;
                pos += contentLines[i].length() + 1; // +1 for \n
            }

            List<Integer> matchStarts = new ArrayList<>();
            outer:
            for (int i = 0; i <= contentLines.length - windowSize; i++) {
                for (int j = 0; j < windowSize; j++) {
                    if (!trimmedContent[i + j].equals(trimmedOld[j])) continue outer;
                }
                matchStarts.add(i);
            }

            if (matchStarts.isEmpty()) return Optional.empty();
            if (!replaceAll && matchStarts.size() > 1) {
                return Optional.of(new ReplaceResult(null, 0, true));
            }

            // apply replacements from back to front to preserve offsets
            List<Integer> toReplace = replaceAll ? matchStarts : List.of(matchStarts.get(0));
            StringBuilder sb = new StringBuilder(content);
            int adjustment = 0;
            for (int startLine : toReplace) {
                int charStart = startOffsets[startLine] + adjustment;
                int charEnd = startLine + windowSize - 1 < contentLines.length - 1
                        ? startOffsets[startLine + windowSize] + adjustment
                        : sb.length();
                // do not include the trailing \n we added for last segment unless present
                if (charEnd > sb.length()) charEnd = sb.length();
                int oldLen = charEnd - charStart;
                sb.replace(charStart, charEnd, newStr);
                adjustment += newStr.length() - oldLen;
            }
            return Optional.of(new ReplaceResult(sb.toString(), toReplace.size(), false));
        }

        private String[] trimAll(String[] lines) {
            String[] r = new String[lines.length];
            for (int i = 0; i < lines.length; i++) r[i] = lines[i].trim();
            return r;
        }
    }

    private final class WhitespaceNormalizedReplacer implements Replacer {
        @Override
        public Optional<ReplaceResult> replace(String content, String old, String newStr, boolean replaceAll) {
            String normContent = normalize(content);
            String normOld = normalize(old);

            if (normOld.isEmpty()) return Optional.empty();

            int first = normContent.indexOf(normOld);
            if (first < 0) return Optional.empty();

            // map normalized position back to original content
            int origStart = mapNormToOrig(content, normContent, first);
            int origEnd = mapNormToOrig(content, normContent, first + normOld.length());
            if (origStart < 0 || origEnd < 0) return Optional.empty();

            if (!replaceAll) {
                int second = normContent.indexOf(normOld, first + normOld.length());
                if (second >= 0) return Optional.of(new ReplaceResult(null, 0, true));
                String replaced = content.substring(0, origStart) + newStr + content.substring(origEnd);
                return Optional.of(new ReplaceResult(replaced, 1, false));
            }

            // replaceAll: find all positions, apply back-to-front
            List<int[]> positions = new ArrayList<>();
            int idx = 0;
            while ((idx = normContent.indexOf(normOld, idx)) >= 0) {
                int s = mapNormToOrig(content, normContent, idx);
                int e = mapNormToOrig(content, normContent, idx + normOld.length());
                if (s >= 0 && e >= 0) positions.add(new int[]{s, e});
                idx += normOld.length();
            }
            if (positions.isEmpty()) return Optional.empty();

            StringBuilder sb = new StringBuilder(content);
            int adj = 0;
            for (int[] p : positions) {
                sb.replace(p[0] + adj, p[1] + adj, newStr);
                adj += newStr.length() - (p[1] - p[0]);
            }
            return Optional.of(new ReplaceResult(sb.toString(), positions.size(), false));
        }

        private String normalize(String s) {
            return s.replaceAll("\\s+", " ").trim();
        }

        /**
         * Walk both strings in parallel (skipping whitespace runs) to map normalized index → original index.
         */
        private int mapNormToOrig(String orig, String norm, int normIdx) {
            int oi = 0, ni = 0;
            while (ni < normIdx && oi < orig.length()) {
                char oc = orig.charAt(oi);
                if (Character.isWhitespace(oc)) {
                    // consume all whitespace in orig as one space in norm
                    while (oi < orig.length() && Character.isWhitespace(orig.charAt(oi))) oi++;
                    if (ni < norm.length() && norm.charAt(ni) == ' ') ni++;
                } else {
                    oi++;
                    ni++;
                }
            }
            // skip leading whitespace that was trimmed in norm
            while (oi < orig.length() && Character.isWhitespace(orig.charAt(oi)) && ni == normIdx) oi++;
            return oi <= orig.length() ? oi : -1;
        }
    }
}
