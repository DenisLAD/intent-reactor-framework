package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atomically replaces the todo list for a session in memory.
 * The store is keyed by session ID; lists are immutable snapshots.
 */
@Component
public class TodoWriteTool implements Tool {

    private static final Set<String> VALID_STATUSES = Set.of("pending", "in_progress", "completed", "cancelled");
    private static final Set<String> VALID_PRIORITIES = Set.of("high", "medium", "low");
    private static final Map<String, Integer> PRIORITY_ORDER = Map.of("high", 0, "medium", 1, "low", 2);

    private static final ConcurrentHashMap<String, List<Map<String, Object>>> TODO_STORE =
            new ConcurrentHashMap<>();

    static void clearStore() {
        TODO_STORE.clear();
    }

    static List<Map<String, Object>> getForSession(String sessionId) {
        return TODO_STORE.getOrDefault(sessionId, List.of());
    }

    @Override
    public String getName() {
        return "todo_write";
    }

    @Override
    public String getDescription() {
        return "Atomically replace the todo list for the current session. " +
                "Completely overwrites any previous list. " +
                "Parameter 'todos': array of task objects, each with " +
                "'content' (string), 'status' (pending|in_progress|completed|cancelled), " +
                "'priority' (high|medium|low).";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "todos", Map.of(
                                "type", "array",
                                "description", "List of todo items to set for this session",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "content", Map.of("type", "string",
                                                        "description", "Task description"),
                                                "status", Map.of("type", "string",
                                                        "enum", List.of("pending", "in_progress", "completed", "cancelled")),
                                                "priority", Map.of("type", "string",
                                                        "enum", List.of("high", "medium", "low"))
                                        ),
                                        "required", List.of("content", "status", "priority")
                                )
                        )
                ),
                "required", List.of("todos")
        );
    }

    // -----------------------------------------------------------------------
    // Package-private for test isolation
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolInput input) {
        Object raw = input.getParameters().get("todos");
        if (raw == null) {
            return ToolResult.error("Parameter 'todos' is required");
        }
        if (!(raw instanceof List<?>)) {
            return ToolResult.error("Parameter 'todos' must be a list");
        }
        List<?> rawList = (List<?>) raw;

        List<Map<String, Object>> validated = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?>)) {
                return ToolResult.error("todos[" + i + "] must be an object");
            }
            Map<String, Object> todo = (Map<String, Object>) item;
            String error = validateItem(todo, i);
            if (error != null) return ToolResult.error(error);
            validated.add(Map.copyOf(todo));
        }

        String sessionId = input.getSessionId() != null ? input.getSessionId() : "default";
        TODO_STORE.put(sessionId, List.copyOf(validated));

        return ToolResult.ok(formatTodos(validated));
    }

    @Override
    public boolean isRisky() {
        return false;
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    private String validateItem(Map<String, Object> item, int index) {
        Object content = item.get("content");
        Object status = item.get("status");
        Object priority = item.get("priority");

        if (!(content instanceof String s) || s.isBlank()) {
            return "todos[" + index + "].content is required and must be non-blank";
        }
        if (!(status instanceof String st) || !VALID_STATUSES.contains(st)) {
            return "todos[" + index + "].status must be one of: " + VALID_STATUSES;
        }
        if (!(priority instanceof String pr) || !VALID_PRIORITIES.contains(pr)) {
            return "todos[" + index + "].priority must be one of: " + VALID_PRIORITIES;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Formatting
    // -----------------------------------------------------------------------

    private String formatTodos(List<Map<String, Object>> todos) {
        if (todos.isEmpty()) {
            return "Todo list cleared (0 items)";
        }

        List<Map<String, Object>> sorted = todos.stream()
                .sorted(Comparator.comparingInt(t ->
                        PRIORITY_ORDER.getOrDefault((String) t.get("priority"), 99)))
                .toList();

        StringBuilder sb = new StringBuilder("Todo list (" + todos.size() + " item(s)):\n");
        for (Map<String, Object> t : sorted) {
            String status = (String) t.get("status");
            String priority = (String) t.get("priority");
            String content = (String) t.get("content");
            sb.append("  [").append(status.toUpperCase()).append("] ")
                    .append("[").append(priority.toUpperCase()).append("] ")
                    .append(content).append("\n");
        }
        return sb.toString().trim();
    }
}
