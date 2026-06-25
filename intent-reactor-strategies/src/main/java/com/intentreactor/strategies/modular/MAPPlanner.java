package com.intentreactor.strategies.modular;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.strategies.config.StrategiesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MAP (Modular Agentic Planner): 5 specialized LLM cognitive modules.
 * <p>
 * 1. TaskDecomposer — once: decomposes goal into subtasks
 * 2. StatePredictor — optional: predicts outcome of action (if useStatePredictor=true)
 * 3. Evaluator      — every evalIntervalSteps: scores progress 0-1
 * 4. ConflictMonitor — on tool errors: detects contradictions
 * 5. TaskCoordinator — every step: decides next action
 * <p>
 * Session attributes: map_phase, map_subtasks, map_subtask_index, map_eval_score,
 * map_eval_step_count, map_conflict_context
 * <p>
 * Activate with: intent-reactor.planning.strategy: map
 */
public class MAPPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(MAPPlanner.class);

    private static final String PHASE_KEY = "map_phase";
    private static final String SUBTASKS_KEY = "map_subtasks";
    private static final String SUBTASK_INDEX_KEY = "map_subtask_index";
    private static final String EVAL_SCORE_KEY = "map_eval_score";
    private static final String EVAL_STEP_COUNT_KEY = "map_eval_step_count";
    private static final String CONFLICT_CONTEXT_KEY = "map_conflict_context";

    // ── TaskDecomposer ──────────────────────────────────────────────────────────
    private static final String DECOMPOSE_SYSTEM =
            "Ты — модуль декомпозиции задач (TaskDecomposer). Разбей цель на упорядоченные " +
                    "атомарные подзадачи. Каждая подзадача — один конкретный шаг.\n\n" +
                    "Доступные инструменты:\n{tools}\n\n" +
                    "Верни JSON-массив строк (не более {maxSubtasks}):\n[\"подзадача 1\", \"подзадача 2\", ...]";

    // ── Evaluator ────────────────────────────────────────────────────────────────
    private static final String EVALUATOR_SYSTEM =
            "Ты — модуль оценки прогресса (Evaluator). Оцени, насколько достигнута цель.\n\n" +
                    "Верни JSON:\n{\"score\": 0.0-1.0, \"assessment\": \"...\", \"next_focus\": \"...\"}";

    // ── ConflictMonitor ─────────────────────────────────────────────────────────
    private static final String CONFLICT_SYSTEM =
            "Ты — модуль обнаружения конфликтов (ConflictMonitor). Проанализируй историю " +
                    "выполнения на предмет противоречий, циклических зависимостей или ошибок.\n\n" +
                    "Верни JSON:\n{\"conflict_detected\": true/false, \"description\": \"...\", \"resolution\": \"...\"}";

    // ── TaskCoordinator ─────────────────────────────────────────────────────────
    private static final String COORDINATOR_SYSTEM =
            "Ты — главный координатор задач (TaskCoordinator). Реши, какое следующее действие " +
                    "нужно для выполнения текущей подзадачи.\n\n" +
                    "Доступные инструменты:\n{tools}\n\n" +
                    "Текущая подзадача ({subtaskIndex}/{totalSubtasks}): {currentSubtask}\n" +
                    "{conflictContext}" +
                    "Верни JSON:\n" +
                    "{\"action\": \"use_tool\"|\"complete_subtask\"|\"done\", " +
                    "\"toolName\": \"...\", \"parameters\": {}, \"rationale\": \"...\"}";

    // ── StatePredictor ──────────────────────────────────────────────────────────
    private static final String STATE_PREDICTOR_SYSTEM =
            "Ты — модуль предсказания состояния (StatePredictor). " +
                    "Предскажи результат выполнения следующей подзадачи.\n\n" +
                    "Верни JSON:\n{\"prediction\": \"...\", \"confidence\": 0.0-1.0}";

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int maxSubtasks;
    private final double progressThreshold;
    private final boolean useConflictMonitor;
    private final boolean useStatePredictor;
    private final int evalIntervalSteps;

    public MAPPlanner(ChatClient chatClient, ToolProvider toolProvider,
                      ObjectMapper objectMapper, StrategiesProperties props) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        StrategiesProperties.MapConfig cfg = props.getMap();
        this.maxSubtasks = cfg.getMaxSubtasks();
        this.progressThreshold = cfg.getProgressThreshold();
        this.useConflictMonitor = cfg.isUseConflictMonitor();
        this.useStatePredictor = cfg.isUseStatePredictor();
        this.evalIntervalSteps = cfg.getEvalIntervalSteps();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "DECOMPOSE");
        String goal = getGoal(session, intent);

        return switch (phase) {
            case "DECOMPOSE" -> decompose(session, goal);
            case "EXECUTE" -> execute(session, goal);
            default -> decompose(session, goal);
        };
    }

    // ── Phase: DECOMPOSE ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Plan decompose(SessionState session, String goal) {
        List<Tool> tools = toolProvider.getAvailableTools(session);
        String toolsList = formatTools(tools);

        String systemPrompt = DECOMPOSE_SYSTEM
                .replace("{tools}", toolsList)
                .replace("{maxSubtasks}", String.valueOf(maxSubtasks));

        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Цель: " + goal)
            ))).call().content();

            List<String> subtasks = parseStringArray(response);
            if (subtasks.size() > maxSubtasks) subtasks = subtasks.subList(0, maxSubtasks);
            if (subtasks.isEmpty()) subtasks = List.of(goal);

            session.getAttributes().put(SUBTASKS_KEY, subtasks);
            session.getAttributes().put(SUBTASK_INDEX_KEY, 0);
            session.getAttributes().put(EVAL_SCORE_KEY, 0.0);
            session.getAttributes().put(EVAL_STEP_COUNT_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "EXECUTE");

            log.debug("[MAP] Decomposed goal into {} subtasks for session {}", subtasks.size(), session.getId());

            if (useStatePredictor && !subtasks.isEmpty()) {
                runStatePredictor(session, subtasks.get(0), goal);
            }

            return execute(session, goal);

        } catch (Exception e) {
            log.warn("[MAP] Decomposition failed: {}", e.getMessage());
            session.getAttributes().put(SUBTASKS_KEY, List.of(goal));
            session.getAttributes().put(SUBTASK_INDEX_KEY, 0);
            session.getAttributes().put(EVAL_SCORE_KEY, 0.0);
            session.getAttributes().put(EVAL_STEP_COUNT_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "EXECUTE");
            return execute(session, goal);
        }
    }

    // ── Phase: EXECUTE ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Plan execute(SessionState session, String goal) {
        List<String> subtasks = (List<String>) session.getAttributes().getOrDefault(
                SUBTASKS_KEY, List.of(goal));
        int subtaskIndex = (int) session.getAttributes().getOrDefault(SUBTASK_INDEX_KEY, 0);
        int evalStepCount = (int) session.getAttributes().getOrDefault(EVAL_STEP_COUNT_KEY, 0);
        String conflictContext = (String) session.getAttributes().getOrDefault(CONFLICT_CONTEXT_KEY, null);

        if (subtaskIndex >= subtasks.size()) {
            return synthesizeDone(session, goal, subtasks);
        }

        // Run ConflictMonitor on tool error
        if (useConflictMonitor && hasRecentToolError(session)) {
            conflictContext = runConflictMonitor(session, goal);
            session.getAttributes().put(CONFLICT_CONTEXT_KEY, conflictContext);
        } else {
            session.getAttributes().put(CONFLICT_CONTEXT_KEY, null);
            conflictContext = null;
        }

        // Run Evaluator periodically
        if (evalStepCount > 0 && evalStepCount % evalIntervalSteps == 0) {
            double score = runEvaluator(session, goal, subtasks, subtaskIndex);
            session.getAttributes().put(EVAL_SCORE_KEY, score);
            if (score >= progressThreshold) {
                log.debug("[MAP] Evaluator score {} >= threshold {}, completing", score, progressThreshold);
                return synthesizeDone(session, goal, subtasks);
            }
        }

        session.getAttributes().put(EVAL_STEP_COUNT_KEY, evalStepCount + 1);

        // Run TaskCoordinator
        List<Tool> tools = toolProvider.getAvailableTools(session);
        String currentSubtask = subtasks.get(subtaskIndex);
        String history = buildHistory(session);

        String conflictBlock = conflictContext != null
                ? "\n[КОНФЛИКТ ОБНАРУЖЕН]: " + conflictContext + "\n"
                : "";

        String systemPrompt = COORDINATOR_SYSTEM
                .replace("{tools}", formatTools(tools))
                .replace("{subtaskIndex}", String.valueOf(subtaskIndex + 1))
                .replace("{totalSubtasks}", String.valueOf(subtasks.size()))
                .replace("{currentSubtask}", currentSubtask)
                .replace("{conflictContext}", conflictBlock);

        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Цель: " + goal + "\n\nИстория:\n" + history)
            ))).call().content();

            Map<String, Object> result = parseJsonObject(response);
            String action = (String) result.getOrDefault("action", "done");
            String rationale = (String) result.getOrDefault("rationale", "");

            if ("complete_subtask".equals(action)) {
                log.debug("[MAP] Completing subtask {} of {}", subtaskIndex + 1, subtasks.size());
                session.getAttributes().put(SUBTASK_INDEX_KEY, subtaskIndex + 1);
                session.getAttributes().put(EVAL_STEP_COUNT_KEY, 0);

                if (useStatePredictor && subtaskIndex + 1 < subtasks.size()) {
                    runStatePredictor(session, subtasks.get(subtaskIndex + 1), goal);
                }

                if (subtaskIndex + 1 >= subtasks.size()) {
                    return synthesizeDone(session, goal, subtasks);
                }
                // chain to next subtask
                return execute(session, goal);
            }

            if ("done".equals(action)) {
                return synthesizeDone(session, goal, subtasks);
            }

            if ("use_tool".equals(action)) {
                String toolName = (String) result.get("toolName");
                if (toolName != null && toolExists(tools, toolName)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = result.get("parameters") instanceof Map
                            ? (Map<String, Object>) result.get("parameters") : new HashMap<>();
                    Action toolAction = new SimpleAction(toolName, params);
                    return new SimplePlan(List.of(
                            SimplePlanStep.act(toolAction, "MAP: " + rationale, false)));
                }
            }

            return new SimplePlan(List.of(SimplePlanStep.reason("MAP Coordinator: " + rationale)));

        } catch (Exception e) {
            log.warn("[MAP] Coordinator call failed: {}", e.getMessage());
            return new SimplePlan(List.of(SimplePlanStep.reason("Переход к следующему шагу")));
        }
    }

    // ── Modules ──────────────────────────────────────────────────────────────────

    private double runEvaluator(SessionState session, String goal, List<String> subtasks, int index) {
        try {
            String history = buildHistory(session);
            String userMsg = "Цель: " + goal + "\nВсе подзадачи: " + subtasks +
                    "\nТекущая подзадача № " + (index + 1) + "\n\nИстория:\n" + history;
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(EVALUATOR_SYSTEM),
                    new UserMessage(userMsg)
            ))).call().content();

            Map<String, Object> result = parseJsonObject(response);
            double score = ((Number) result.getOrDefault("score", 0.5)).doubleValue();
            log.debug("[MAP] Evaluator score: {} for session {}", score, session.getId());
            return score;
        } catch (Exception e) {
            log.warn("[MAP] Evaluator failed: {}", e.getMessage());
            return 0.0;
        }
    }

    private String runConflictMonitor(SessionState session, String goal) {
        try {
            String history = buildHistory(session);
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(CONFLICT_SYSTEM),
                    new UserMessage("Цель: " + goal + "\n\nИстория:\n" + history)
            ))).call().content();

            Map<String, Object> result = parseJsonObject(response);
            if (Boolean.TRUE.equals(result.get("conflict_detected"))) {
                String description = (String) result.getOrDefault("description", "");
                String resolution = (String) result.getOrDefault("resolution", "");
                log.debug("[MAP] Conflict detected for session {}: {}", session.getId(), description);
                return description + ". Разрешение: " + resolution;
            }
        } catch (Exception e) {
            log.warn("[MAP] ConflictMonitor failed: {}", e.getMessage());
        }
        return null;
    }

    private void runStatePredictor(SessionState session, String nextSubtask, String goal) {
        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(STATE_PREDICTOR_SYSTEM),
                    new UserMessage("Цель: " + goal + "\nСледующая подзадача: " + nextSubtask)
            ))).call().content();

            Map<String, Object> result = parseJsonObject(response);
            String prediction = (String) result.getOrDefault("prediction", "");
            if (!prediction.isBlank()) {
                session.addMessage(Message.system("[MAP:StatePredictor] Прогноз: " + prediction));
            }
        } catch (Exception e) {
            log.warn("[MAP] StatePredictor failed: {}", e.getMessage());
        }
    }

    // ── Synthesize ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Plan synthesizeDone(SessionState session, String goal, List<String> subtasks) {
        String history = buildHistory(session);
        String summary = "Цель: " + goal + "\nВыполненные подзадачи: " + subtasks +
                "\n\nИстория выполнения:\n" + history;
        return new SimplePlan(List.of(SimplePlanStep.done(summary)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private boolean hasRecentToolError(SessionState session) {
        List<Message> messages = session.getMessages();
        int start = Math.max(0, messages.size() - 3);
        for (int i = start; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getContent() != null && msg.getContent().contains("[TOOL_ERROR]")) return true;
        }
        return false;
    }

    private boolean toolExists(List<Tool> tools, String toolName) {
        return tools.stream().anyMatch(t -> t.getName().equals(toolName));
    }

    private String buildHistory(SessionState session) {
        List<Message> messages = session.getMessages();
        int start = Math.max(0, messages.size() - 10);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            Message msg = messages.get(i);
            sb.append(msg.getRole().name()).append(": ").append(truncate(msg.getContent(), 300)).append("\n");
        }
        return sb.toString();
    }

    private String formatTools(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringArray(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            return objectMapper.readValue(cleaned, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            return objectMapper.readValue(cleaned, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String stripMarkdownFences(String s) {
        if (!s.startsWith("```")) return s;
        int newline = s.indexOf('\n');
        if (newline < 0) return s;
        s = s.substring(newline + 1);
        int fence = s.lastIndexOf("```");
        if (fence >= 0) s = s.substring(0, fence);
        return s.strip();
    }

    private String getGoal(SessionState session, IntentAnalysisResult intent) {
        if (session.getPlanState() != null) {
            String g = session.getPlanState().getGoalDescription();
            if (g != null && !g.isBlank()) return g;
        }
        if (intent != null && intent.getReasoningSuggestion() != null
                && !intent.getReasoningSuggestion().isBlank()) {
            return intent.getReasoningSuggestion();
        }
        if (intent != null && !intent.getIntents().isEmpty()) {
            return intent.getIntents().get(0).getName();
        }
        return "unknown";
    }
}
