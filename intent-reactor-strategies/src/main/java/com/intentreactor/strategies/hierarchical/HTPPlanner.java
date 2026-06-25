package com.intentreactor.strategies.hierarchical;

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
import java.util.Optional;

/**
 * HTP (HyperTree Planning): hierarchical goal decomposition into subgoals with constraints.
 * <p>
 * Unlike ToT (tree of thoughts), HTP's nodes are goal-oriented subgoals, each getting
 * its own mini-plan executed sequentially. Supports iterative refinement.
 * <p>
 * Phases: DECOMPOSE → PLAN_NODE → EXECUTE → REFINE → ADVANCE → SYNTHESIZE
 * <p>
 * Session attributes: htp_tree (HyperTree), htp_phase
 * <p>
 * Activate with: intent-reactor.planning.strategy: htp
 */
public class HTPPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(HTPPlanner.class);

    private static final String TREE_KEY = "htp_tree";
    private static final String PHASE_KEY = "htp_phase";

    private static final String DECOMPOSE_SYSTEM =
            "Ты — архитектор планирования. Декомпозируй цель на упорядоченные подцели.\n" +
                    "Для каждой подцели укажи ограничения (бюджет, время, требования).\n\n" +
                    "Доступные инструменты:\n{tools}\n\n" +
                    "Верни JSON-массив (не более {maxSubgoals} элементов):\n" +
                    "[{\"subgoal\": \"...\", \"constraints\": [\"...\", \"...\"]}]";

    private static final String PLAN_NODE_SYSTEM =
            "Ты — планировщик шагов. Составь конкретный план для выполнения подцели.\n" +
                    "Подцель: {subgoal}\n" +
                    "Ограничения: {constraints}\n\n" +
                    "Доступные инструменты:\n{tools}\n\n" +
                    "{refinementContext}" +
                    "Верни JSON-массив шагов (не более {maxSteps}):\n" +
                    "[{\"toolName\": \"...\", \"parameters\": {}, \"description\": \"...\"}]\n\n" +
                    "Для шагов без инструмента используй null в поле toolName.";

    private static final String REFINE_SYSTEM =
            "Ты — ревьюер выполнения. Оцени, достигнута ли подцель по итогам выполнения шагов.\n\n" +
                    "Подцель: {subgoal}\n" +
                    "Ограничения: {constraints}\n\n" +
                    "Верни JSON:\n{\"achieved\": true/false, \"reason\": \"...\", \"missing\": \"...\"}";

    private static final String SYNTHESIZE_SYSTEM =
            "Ты — финальный аналитик. Синтезируй итоговый ответ на основе выполненных подцелей.";

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int maxSubgoals;
    private final int maxStepsPerNode;
    private final boolean refinementEnabled;
    private final int maxRefinementRetries;

    public HTPPlanner(ChatClient chatClient, ToolProvider toolProvider,
                      ObjectMapper objectMapper, StrategiesProperties props) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        StrategiesProperties.HtpConfig cfg = props.getHtp();
        this.maxSubgoals = cfg.getMaxSubgoals();
        this.maxStepsPerNode = cfg.getMaxStepsPerNode();
        this.refinementEnabled = cfg.isRefinementEnabled();
        this.maxRefinementRetries = cfg.getMaxRefinementRetries();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "DECOMPOSE");
        String goal = getGoal(session, intent);

        return switch (phase) {
            case "DECOMPOSE" -> decompose(session, goal);
            case "PLAN_NODE" -> planNode(session, goal, null);
            case "EXECUTE" -> execute(session, goal);
            case "REFINE" -> refine(session, goal);
            case "ADVANCE" -> advance(session, goal);
            case "SYNTHESIZE" -> synthesize(session, goal);
            default -> decompose(session, goal);
        };
    }

    // ── DECOMPOSE ────────────────────────────────────────────────────────────────

    private Plan decompose(SessionState session, String goal) {
        List<Tool> tools = toolProvider.getAvailableTools(session);
        String systemPrompt = DECOMPOSE_SYSTEM
                .replace("{tools}", formatTools(tools))
                .replace("{maxSubgoals}", String.valueOf(maxSubgoals));

        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Цель: " + goal)
            ))).call().content();

            List<Map<String, Object>> subgoalDefs = parseJsonArray(response);

            HyperTree tree = HyperTree.withRoot(goal);
            String rootId = tree.getRootId();

            for (Map<String, Object> def : subgoalDefs) {
                String subgoal = (String) def.getOrDefault("subgoal", "подзадача");
                @SuppressWarnings("unchecked")
                List<String> constraints = def.get("constraints") instanceof List
                        ? (List<String>) def.get("constraints") : List.of();

                HyperNode node = new HyperNode(subgoal, constraints, rootId, 1);
                tree.getNodes().put(node.getId(), node);
                tree.getNodes().get(rootId).getChildIds().add(node.getId());
            }

            if (tree.getNodes().size() <= 1) {
                // No subgoals parsed — single-node fallback
                HyperNode single = new HyperNode(goal, List.of(), rootId, 1);
                tree.getNodes().put(single.getId(), single);
                tree.getNodes().get(rootId).getChildIds().add(single.getId());
            }

            saveTree(session, tree);
            session.getAttributes().put(PHASE_KEY, "PLAN_NODE");

            log.debug("[HTP] Decomposed into {} subgoals for session {}",
                    tree.getNodes().size() - 1, session.getId());

            return planNode(session, goal, null);

        } catch (Exception e) {
            log.warn("[HTP] Decompose failed: {}", e.getMessage());
            HyperTree tree = HyperTree.withRoot(goal);
            HyperNode single = new HyperNode(goal, List.of(), tree.getRootId(), 1);
            tree.getNodes().put(single.getId(), single);
            tree.getNodes().get(tree.getRootId()).getChildIds().add(single.getId());
            saveTree(session, tree);
            session.getAttributes().put(PHASE_KEY, "PLAN_NODE");
            return planNode(session, goal, null);
        }
    }

    // ── PLAN_NODE ────────────────────────────────────────────────────────────────

    private Plan planNode(SessionState session, String goal, String refinementContext) {
        HyperTree tree = loadTree(session);
        Optional<HyperNode> optNode = tree.nextPendingNode();
        if (optNode.isEmpty()) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }

        HyperNode node = optNode.get();
        tree.setCurrentNodeId(node.getId());
        node.setStatus("IN_PROGRESS");

        List<Tool> tools = toolProvider.getAvailableTools(session);
        String history = buildHistory(session);
        String refCtx = refinementContext != null
                ? "[REFINEMENT] Предыдущая попытка не достигла цели: " + refinementContext + "\n\n"
                : "";

        String systemPrompt = PLAN_NODE_SYSTEM
                .replace("{subgoal}", node.getSubgoal())
                .replace("{constraints}", String.join(", ", node.getConstraints()))
                .replace("{tools}", formatTools(tools))
                .replace("{maxSteps}", String.valueOf(maxStepsPerNode))
                .replace("{refinementContext}", refCtx);

        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Общая цель: " + goal + "\n\nКонтекст:\n" + history)
            ))).call().content();

            List<Map<String, Object>> stepDefs = parseJsonArray(response);
            List<HtpStep> steps = new ArrayList<>();
            for (Map<String, Object> def : stepDefs) {
                String toolName = (String) def.get("toolName");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = def.get("parameters") instanceof Map
                        ? (Map<String, Object>) def.get("parameters") : new HashMap<>();
                String description = (String) def.getOrDefault("description", "шаг");
                steps.add(new HtpStep(toolName, params, description));
            }
            if (steps.isEmpty()) steps.add(new HtpStep(null, Map.of(), "Выполнить: " + node.getSubgoal()));

            node.setSteps(steps);
            node.setCurrentStepIndex(0);
            saveTree(session, tree);
            session.getAttributes().put(PHASE_KEY, "EXECUTE");

            log.debug("[HTP] Planned {} steps for subgoal '{}' in session {}", steps.size(), node.getSubgoal(), session.getId());
            return execute(session, goal);

        } catch (Exception e) {
            log.warn("[HTP] PlanNode failed: {}", e.getMessage());
            node.setSteps(List.of(new HtpStep(null, Map.of(), "Выполнить: " + node.getSubgoal())));
            node.setCurrentStepIndex(0);
            saveTree(session, tree);
            session.getAttributes().put(PHASE_KEY, "EXECUTE");
            return execute(session, goal);
        }
    }

    // ── EXECUTE ──────────────────────────────────────────────────────────────────

    private Plan execute(SessionState session, String goal) {
        HyperTree tree = loadTree(session);
        HyperNode node = tree.getCurrent();
        if (node == null) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }

        // Absorb last observation into previous step
        if (node.getCurrentStepIndex() > 0) {
            List<Message> messages = session.getMessages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                if (msg.getRole() == Message.Role.SYSTEM) {
                    HtpStep prevStep = node.getSteps().get(node.getCurrentStepIndex() - 1);
                    if (prevStep.getObservation() == null) {
                        prevStep.setObservation(msg.getContent());
                    }
                    break;
                }
            }
        }

        // Check if all steps done
        if (node.getCurrentStepIndex() >= node.getSteps().size()) {
            saveTree(session, tree);
            if (refinementEnabled) {
                session.getAttributes().put(PHASE_KEY, "REFINE");
                return refine(session, goal);
            } else {
                node.setStatus("DONE");
                saveTree(session, tree);
                session.getAttributes().put(PHASE_KEY, "ADVANCE");
                return advance(session, goal);
            }
        }

        HtpStep step = node.getSteps().get(node.getCurrentStepIndex());
        node.setCurrentStepIndex(node.getCurrentStepIndex() + 1);
        saveTree(session, tree);

        if (step.getToolName() != null && !step.getToolName().isBlank()) {
            Action action = new SimpleAction(step.getToolName(), step.getParameters());
            return new SimplePlan(List.of(SimplePlanStep.act(action, step.getDescription(), false)));
        }
        return new SimplePlan(List.of(SimplePlanStep.reason(step.getDescription())));
    }

    // ── REFINE ───────────────────────────────────────────────────────────────────

    private Plan refine(SessionState session, String goal) {
        HyperTree tree = loadTree(session);
        HyperNode node = tree.getCurrent();
        if (node == null) {
            session.getAttributes().put(PHASE_KEY, "ADVANCE");
            return advance(session, goal);
        }

        String observationLog = buildObservationLog(node);

        String systemPrompt = REFINE_SYSTEM
                .replace("{subgoal}", node.getSubgoal())
                .replace("{constraints}", String.join(", ", node.getConstraints()));

        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Журнал выполнения:\n" + observationLog)
            ))).call().content();

            Map<String, Object> result = parseJsonObject(response);
            boolean achieved = Boolean.TRUE.equals(result.get("achieved"));

            if (achieved) {
                node.setStatus("DONE");
                saveTree(session, tree);
                log.debug("[HTP] Subgoal '{}' achieved in session {}", node.getSubgoal(), session.getId());
                session.getAttributes().put(PHASE_KEY, "ADVANCE");
                return advance(session, goal);
            }

            // Not achieved — try refinement
            String missing = (String) result.getOrDefault("missing", "неизвестно");
            node.setRefinementRetries(node.getRefinementRetries() + 1);

            if (node.getRefinementRetries() <= maxRefinementRetries) {
                node.setStatus("NEEDS_REFINEMENT");
                node.setSteps(new ArrayList<>());
                node.setCurrentStepIndex(0);
                saveTree(session, tree);
                log.debug("[HTP] Refining subgoal '{}', retry {}/{} in session {}",
                        node.getSubgoal(), node.getRefinementRetries(), maxRefinementRetries, session.getId());
                session.getAttributes().put(PHASE_KEY, "PLAN_NODE");
                return planNode(session, goal, missing);
            }

            // Max retries exceeded
            node.setStatus("FAILED");
            saveTree(session, tree);
            log.debug("[HTP] Subgoal '{}' FAILED after {} retries in session {}",
                    node.getSubgoal(), node.getRefinementRetries(), session.getId());
            session.getAttributes().put(PHASE_KEY, "ADVANCE");
            return advance(session, goal);

        } catch (Exception e) {
            log.warn("[HTP] Refine failed: {}", e.getMessage());
            node.setStatus("DONE");
            saveTree(session, tree);
            session.getAttributes().put(PHASE_KEY, "ADVANCE");
            return advance(session, goal);
        }
    }

    // ── ADVANCE ──────────────────────────────────────────────────────────────────

    private Plan advance(SessionState session, String goal) {
        HyperTree tree = loadTree(session);
        Optional<HyperNode> nextNode = tree.nextPendingNode();

        if (nextNode.isPresent()) {
            saveTree(session, tree);
            session.getAttributes().put(PHASE_KEY, "PLAN_NODE");
            return planNode(session, goal, null);
        }

        session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
        return synthesize(session, goal);
    }

    // ── SYNTHESIZE ───────────────────────────────────────────────────────────────

    private Plan synthesize(SessionState session, String goal) {
        HyperTree tree = loadTree(session);
        StringBuilder summary = new StringBuilder("Цель: " + goal + "\n\nВыполненные подцели:\n");

        for (HyperNode node : tree.getNodes().values()) {
            if (node.getId().equals(tree.getRootId())) continue;
            summary.append("- [").append(node.getStatus()).append("] ").append(node.getSubgoal()).append("\n");
            String obsLog = buildObservationLog(node);
            if (!obsLog.isBlank()) {
                summary.append("  Результаты:\n");
                obsLog.lines().forEach(line -> summary.append("  ").append(line).append("\n"));
            }
        }

        try {
            String finalAnswer = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(SYNTHESIZE_SYSTEM),
                    new UserMessage(summary.toString())
            ))).call().content();
            return new SimplePlan(List.of(SimplePlanStep.done(finalAnswer)));
        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done(summary.toString())));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private String buildObservationLog(HyperNode node) {
        StringBuilder sb = new StringBuilder();
        for (HtpStep step : node.getSteps()) {
            sb.append("- ").append(step.getDescription());
            if (step.getObservation() != null) {
                sb.append(" → ").append(truncate(step.getObservation(), 200));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildHistory(SessionState session) {
        List<Message> messages = session.getMessages();
        int start = Math.max(0, messages.size() - 6);
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

    private void saveTree(SessionState session, HyperTree tree) {
        session.getAttributes().put(TREE_KEY, tree);
    }

    private HyperTree loadTree(SessionState session) {
        Object raw = session.getAttributes().get(TREE_KEY);
        if (raw == null) return HyperTree.withRoot("unknown");
        return objectMapper.convertValue(raw, HyperTree.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonArray(String response) {
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
