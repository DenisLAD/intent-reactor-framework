package com.intentreactor.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static utilities for extracting and cleaning LLM response text.
 * Centralises JSON extraction logic shared by DefaultReACTPlanner,
 * DefaultIntentPreprocessor, and any future response parsers.
 */
public final class LlmResponseParser {

    private LlmResponseParser() {}

    /**
     * Strips leading/trailing {@code ```} markdown code fences from a response.
     */
    public static String stripMarkdownFences(String response) {
        String s = response.strip();
        if (!s.startsWith("```")) return s;
        int newline = s.indexOf('\n');
        if (newline < 0) return s;
        s = s.substring(newline + 1);
        int closingFence = s.lastIndexOf("```");
        if (closingFence >= 0) s = s.substring(0, closingFence);
        return s.strip();
    }

    /**
     * Extracts every complete top-level JSON object ({...}) from the given text.
     * Returns them in document order.
     */
    public static List<String> extractAllJsonCandidates(String response) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        for (int i = 0; i < response.length(); i++) {
            char c = response.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped character
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}' && depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        result.add(response.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Attempts to close an unclosed JSON object by appending the missing closing braces.
     * Returns null if the text does not start a JSON object or nesting is too deep.
     */
    public static String repairTruncatedJson(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') { i++; }
                else if (c == '"') { inString = false; }
            } else {
                if (c == '"') { inString = true; }
                else if (c == '{') { depth++; }
                else if (c == '}' && depth > 0) { depth--; }
            }
        }
        if (depth <= 0 || depth > 10) return null;
        StringBuilder sb = new StringBuilder(text.strip());
        if (inString) sb.append('"');
        for (int i = 0; i < depth; i++) sb.append('}');
        return sb.toString();
    }

    /**
     * Extracts a JSON object from an LLM response, preferring objects that contain
     * any of the given {@code preferredKeys}. Falls back to the last candidate.
     *
     * @param response      raw LLM response text (may include markdown fences, prose, etc.)
     * @param objectMapper  used to validate and inspect candidates
     * @param preferredKeys domain-specific keys that identify the "correct" JSON object
     *                      (e.g. {@code Set.of("done","failed","toolName")} for ReACT,
     *                       {@code Set.of("intents","reasoningSuggestion")} for intent analysis)
     */
    public static String extractJson(String response, ObjectMapper objectMapper,
                                     Set<String> preferredKeys) {
        if (response == null) throw new IllegalArgumentException("Empty LLM response");
        String stripped = stripMarkdownFences(response);
        List<String> candidates = extractAllJsonCandidates(stripped);
        if (candidates.isEmpty()) {
            String repaired = repairTruncatedJson(stripped);
            if (repaired != null) candidates = extractAllJsonCandidates(repaired);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No JSON object found in response: " + response);
        }
        for (String candidate : candidates) {
            try {
                Map<?, ?> m = objectMapper.readValue(candidate, Map.class);
                for (String key : preferredKeys) {
                    if (m.containsKey(key)) return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * Formats a list of tools for inclusion in an LLM system prompt.
     */
    public static String formatTools(List<Tool> tools, ObjectMapper objectMapper) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            try {
                sb.append("  Parameters: ").append(objectMapper.writeValueAsString(tool.getParameterSchema())).append("\n");
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }
}
