package com.intentreactor.strategies.decomposition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.StepType;
import com.intentreactor.strategies.config.StrategiesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Least-to-Most planner: decomposes the goal into ordered subproblems (simplest first),
 * then solves each one in order, using previous solutions as context for subsequent ones.
 * <p>
 * Phases stored in session.attributes:
 * ltm_phase    : DECOMPOSE | SOLVE
 * ltm_tasks    : List<Map> [{id, task, depends_on:[]}]
 * ltm_results  : Map<Integer, String>
 * ltm_index    : int
 * <p>
 * Activate with: intent-reactor.planning.strategy: least-to-most
 */
public class LeastToMostPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(LeastToMostPlanner.class);

    private static final String PHASE_KEY = "ltm_phase";
    private static final String TASKS_KEY = "ltm_tasks";
    private static final String RESULTS_KEY = "ltm_results";
    private static final String INDEX_KEY = "ltm_index";

    private static final String DECOMPOSE_SYSTEM =
            "Ты эксперт по декомпозиции задач. Разбей задачу на подзадачи, " +
                    "упорядоченные от простейшей к наиболее сложной. Каждая подзадача должна быть " +
                    "атомарной и конкретной. Результаты более простых задач могут использоваться в сложных.\n\n" +
                    "Верни JSON-массив:\n" +
                    "[\n" +
                    "  {\"id\": 1, \"task\": \"Найти базовую информацию о X\", \"depends_on\": []},\n" +
                    "  {\"id\": 2, \"task\": \"Применить X к Y\", \"depends_on\": [1]}\n" +
                    "]\n\n" +
                    "Минимум 2, максимум {max} подзадач. Порядок от простого к сложному обязателен.";

    private static final String SOLVE_SYSTEM =
            "Ты решаешь подзадачу. У тебя есть контекст из уже решённых более простых подзадач. " +
                    "Дай чёткий, конкретный ответ на подзадачу. Используй контекст если это помогает.";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int maxSubproblems;

    public LeastToMostPlanner(ChatClient chatClient, ObjectMapper objectMapper,
                              StrategiesProperties props) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.maxSubproblems = props.getLeastToMost().getMaxSubproblems();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "DECOMPOSE");
        String goal = getGoal(session, intent);

        return switch (phase) {
            case "DECOMPOSE" -> decompose(session, goal);
            case "SOLVE" -> solveNext(session, goal);
            default -> decompose(session, goal);
        };
    }

    private Plan decompose(SessionState session, String goal) {
        try {
            String systemPrompt = DECOMPOSE_SYSTEM.replace("{max}", String.valueOf(maxSubproblems));
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Задача: " + goal)
            ))).call().content();

            List<Map<String, Object>> tasks = parseTasks(response);
            if (tasks.size() > maxSubproblems) tasks = tasks.subList(0, maxSubproblems);

            session.getAttributes().put(TASKS_KEY, tasks);
            session.getAttributes().put(RESULTS_KEY, new LinkedHashMap<>());
            session.getAttributes().put(INDEX_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "SOLVE");

            log.debug("[LeastToMost] Decomposed into {} tasks for session {}", tasks.size(), session.getId());
            return solveNext(session, goal);

        } catch (Exception e) {
            log.warn("[LeastToMost] Decomposition failed: {}", e.getMessage());
            return new SimplePlan(List.of(SimplePlanStep.fail("Decomposition failed: " + e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private Plan solveNext(SessionState session, String goal) {
        List<Map<String, Object>> tasks =
                (List<Map<String, Object>>) session.getAttributes().get(TASKS_KEY);
        Map<String, String> results =
                (Map<String, String>) session.getAttributes().get(RESULTS_KEY);
        int index = (int) session.getAttributes().getOrDefault(INDEX_KEY, 0);

        // Capture result from last OBSERVE
        if (index > 0) {
            List<Message> messages = session.getMessages();
            if (!messages.isEmpty()) {
                Message last = messages.get(messages.size() - 1);
                if (last.getRole() == Message.Role.SYSTEM || last.getRole() == Message.Role.ASSISTANT) {
                    results.put(String.valueOf(index - 1), last.getContent());
                    session.getAttributes().put(RESULTS_KEY, results);
                }
            }
        }

        if (index >= tasks.size()) {
            return synthesizeFinal(session, goal, results);
        }

        Map<String, Object> task = tasks.get(index);
        String taskDesc = (String) task.get("task");
        session.getAttributes().put(INDEX_KEY, index + 1);

        // Build context from previous results
        StringBuilder context = new StringBuilder();
        if (!results.isEmpty()) {
            context.append("\n\nРезультаты предыдущих подзадач:\n");
            results.forEach((k, v) -> context.append("Подзадача ").append(k).append(": ").append(v).append("\n"));
        }

        session.addMessage(Message.system("[SUBTASK " + (index + 1) + "/" + tasks.size() + "] " + taskDesc));
        log.debug("[LeastToMost] Solving task {}/{} for session {}", index + 1, tasks.size(), session.getId());

        return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null,
                "Solving: " + taskDesc + context, false)));
    }

    private Plan synthesizeFinal(SessionState session, String goal, Map<String, String> results) {
        String combined = results.values().stream()
                .collect(Collectors.joining("\n---\n"));
        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage("Синтезируй финальный ответ из результатов всех подзадач."),
                    new UserMessage("Исходная задача: " + goal + "\n\nРезультаты подзадач:\n" + combined)
            ))).call().content();
            return new SimplePlan(List.of(SimplePlanStep.done(response)));
        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done(combined)));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTasks(String response) {
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
        return "unknown";
    }
}
