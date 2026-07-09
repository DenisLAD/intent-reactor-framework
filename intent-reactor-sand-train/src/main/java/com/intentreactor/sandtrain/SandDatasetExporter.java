package com.intentreactor.sandtrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports SAND training records to JSONL in OpenAI fine-tuning format.
 * Each line is a self-contained conversation: system + user (context + candidates) + assistant (selection).
 */
public class SandDatasetExporter {

    private static final Logger log = LoggerFactory.getLogger(SandDatasetExporter.class);

    private final ObjectMapper objectMapper;

    public SandDatasetExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJsonl(List<SandTrainingRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (SandTrainingRecord record : records) {
            try {
                String line = toJsonlLine(record);
                sb.append(line).append('\n');
            } catch (Exception e) {
                log.warn("[SandExport] Skipping record step={} session={}: {}",
                        record.getStepIndex(), record.getSessionId(), e.getMessage());
            }
        }
        return sb.toString();
    }

    private String toJsonlLine(SandTrainingRecord record) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();

        messages.add(message("system",
                "You are an action selection expert. Given the agent's goal, context, and candidate actions " +
                "with predicted outcomes, select the best action index. Respond with JSON only."));

        String userContent = buildUserContent(record);
        messages.add(message("user", userContent));

        Map<String, Object> selected = findCandidate(record.getCandidates(), record.getSelectedIndex());
        String assistantContent = objectMapper.writeValueAsString(Map.of(
                "selectedIndex", record.getSelectedIndex(),
                "toolName", selected != null ? selected.get("toolName") : "",
                "reasoning", selected != null ? selected.get("reasoning") : ""
        ));
        messages.add(message("assistant", assistantContent));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("messages", messages);
        return objectMapper.writeValueAsString(entry);
    }

    private String buildUserContent(SandTrainingRecord record) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(record.getGoal()).append("\n\n");
        sb.append("Context:\n").append(record.getContextSummary()).append("\n\n");
        sb.append("Candidates:\n");
        for (Map<String, Object> c : record.getCandidates()) {
            sb.append("  [").append(c.get("index")).append("] tool=").append(c.get("toolName"))
              .append(" params=").append(objectMapper.writeValueAsString(c.get("parameters")))
              .append(" prediction=").append(c.get("prediction"))
              .append(" score=").append(c.get("score"))
              .append("\n");
        }
        return sb.toString();
    }

    private Map<String, Object> findCandidate(List<Map<String, Object>> candidates, int index) {
        if (candidates == null) return null;
        return candidates.stream()
                .filter(c -> index == ((Number) c.getOrDefault("index", -1)).intValue())
                .findFirst().orElse(null);
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
