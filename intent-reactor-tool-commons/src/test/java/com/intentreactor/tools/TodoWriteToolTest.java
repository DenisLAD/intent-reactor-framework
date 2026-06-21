package com.intentreactor.tools;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoWriteToolTest {

    private final TodoWriteTool tool = new TodoWriteTool();

    @BeforeEach
    void clearStore() {
        TodoWriteTool.clearStore();
    }

    private ToolInput input(Map<String, Object> params, String sessionId) {
        return new ToolInput(params, sessionId);
    }

    private Map<String, Object> todo(String content, String status, String priority) {
        return Map.of("content", content, "status", status, "priority", priority);
    }

    @Test
    void storesAndReturnsTodos() {
        ToolResult result = tool.execute(input(Map.of("todos", List.of(
                todo("Fix bug", "pending", "high"),
                todo("Write docs", "in_progress", "medium")
        )), "s1"));

        assertThat(result.isSuccess()).isTrue();
        String out = result.getData().toString();
        assertThat(out).contains("Fix bug");
        assertThat(out).contains("Write docs");
    }

    @Test
    void replacesExistingTodos() {
        tool.execute(input(Map.of("todos", List.of(todo("Old task", "pending", "low"))), "s1"));
        tool.execute(input(Map.of("todos", List.of(todo("New task", "in_progress", "high"))), "s1"));

        List<Map<String, Object>> stored = TodoWriteTool.getForSession("s1");
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).get("content")).isEqualTo("New task");
    }

    @Test
    void sessionIsolation() {
        tool.execute(input(Map.of("todos", List.of(todo("Session A task", "pending", "high"))), "a"));
        tool.execute(input(Map.of("todos", List.of(todo("Session B task", "pending", "low"))), "b"));

        assertThat(TodoWriteTool.getForSession("a").get(0).get("content")).isEqualTo("Session A task");
        assertThat(TodoWriteTool.getForSession("b").get(0).get("content")).isEqualTo("Session B task");
    }

    @Test
    void nullSessionIdUsesDefault() {
        ToolResult result = tool.execute(new ToolInput(Map.of("todos", List.of(
                todo("Default task", "pending", "medium")
        )), null));

        assertThat(result.isSuccess()).isTrue();
        assertThat(TodoWriteTool.getForSession("default")).hasSize(1);
    }

    @Test
    void outputSortedByPriority() {
        ToolResult result = tool.execute(input(Map.of("todos", List.of(
                todo("Low task", "pending", "low"),
                todo("High task", "pending", "high"),
                todo("Medium task", "pending", "medium")
        )), "s1"));

        String out = result.getData().toString();
        int highIdx = out.indexOf("High task");
        int mediumIdx = out.indexOf("Medium task");
        int lowIdx = out.indexOf("Low task");
        assertThat(highIdx).isLessThan(mediumIdx);
        assertThat(mediumIdx).isLessThan(lowIdx);
    }

    @Test
    void outputShowsStatusAndPriority() {
        tool.execute(input(Map.of("todos", List.of(
                todo("My task", "completed", "high")
        )), "s1"));

        ToolResult result = tool.execute(input(Map.of("todos", List.of(
                todo("My task", "completed", "high")
        )), "s2"));

        String out = result.getData().toString();
        assertThat(out).contains("COMPLETED");
        assertThat(out).contains("HIGH");
    }

    @Test
    void errorOnMissingTodos() {
        ToolResult result = tool.execute(input(Map.of(), "s1"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("todos");
    }

    @Test
    void errorWhenTodosIsNotAList() {
        ToolResult result = tool.execute(input(Map.of("todos", "not a list"), "s1"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("list");
    }

    @Test
    void errorOnInvalidStatus() {
        ToolResult result = tool.execute(input(Map.of("todos", List.of(
                Map.of("content", "task", "status", "unknown", "priority", "high")
        )), "s1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("status");
    }

    @Test
    void errorOnInvalidPriority() {
        ToolResult result = tool.execute(input(Map.of("todos", List.of(
                Map.of("content", "task", "status", "pending", "priority", "critical")
        )), "s1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("priority");
    }

    @Test
    void errorOnMissingContent() {
        ToolResult result = tool.execute(input(Map.of("todos", List.of(
                Map.of("status", "pending", "priority", "low")
        )), "s1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("content");
    }

    @Test
    void handlesEmptyList() {
        ToolResult result = tool.execute(input(Map.of("todos", List.of()), "s1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(TodoWriteTool.getForSession("s1")).isEmpty();
        assertThat(result.getData().toString()).containsIgnoringCase("cleared");
    }
}
