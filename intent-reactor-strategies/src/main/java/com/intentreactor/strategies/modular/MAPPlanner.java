package com.intentreactor.strategies.modular;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.util.PromptLoader;
import com.intentreactor.strategies.config.StrategiesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MAP (Modular Agentic Planner): iteratively refines a subgoal decomposition through four LLM
 * modules — TaskDecomposer, Evaluator, ConflictMonitor, and optional StatePredictor — then
 * injects the approved plan as a pinned message and delegates step execution to a base ReACT planner.
 */
public class MAPPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(MAPPlanner.class);

    private static final String PHASE_KEY = "map_phase";

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final Planner delegate;
    private final PromptLoader promptLoader;

    private final int maxSubtasks;
    private final int maxPlanningIterations;
    private final double confidenceThreshold;
    private final boolean useConflictMonitor;
    private final boolean useStatePredictor;

    private final String decomposePrompt;
    private final String evaluatePrompt;
    private final String conflictPrompt;
    private final String predictPrompt;
    private final String executeSystemPrompt;

    private final StrategiesProperties.LabelsConfig labels;

    public MAPPlanner(ChatClient chatClient, ToolProvider toolProvider, ObjectMapper objectMapper,
                      StrategiesProperties props, Planner delegate, PromptLoader promptLoader) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.delegate = delegate;
        this.promptLoader = promptLoader;

        StrategiesProperties.MapConfig cfg = props.getMap();
        this.maxSubtasks = cfg.getMaxSubtasks();
        this.maxPlanningIterations = cfg.getMaxPlanningIterations();
        this.confidenceThreshold = cfg.getConfidenceThreshold();
        this.useConflictMonitor = cfg.isUseConflictMonitor();
        this.useStatePredictor = cfg.isUseStatePredictor();

        this.decomposePrompt = props.getPrompts().getMapDecompose();
        this.evaluatePrompt = props.getPrompts().getMapEvaluate();
        this.conflictPrompt = props.getPrompts().getMapConflict();
        this.predictPrompt = props.getPrompts().getMapPredict();
        this.executeSystemPrompt = props.getPrompts().getMapExecuteSystem();

        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "PLAN");
        String goal = getGoal(session, intent);
        return "PLAN".equals(phase)
                ? runPlanningPhase(session, intent, goal)
                : delegate.plan(session, intent);
    }

    // ── Planning phase ──────────────────────────────────────────────────────────

    private Plan runPlanningPhase(SessionState session, IntentAnalysisResult intent, String goal) {
        List<Tool> tools = toolProvider.getAvailableTools(session);
        List<Subgoal> best = decompose(goal, tools, null);

        for (int i = 1; i < maxPlanningIterations; i++) {
            if (useStatePredictor) {
                for (Subgoal sg : best) {
                    predictState(session, goal, sg);
                }
            }
            double score = evaluate(session, goal, best);
            String conflictCtx = useConflictMonitor ? checkConflicts(session, goal, best) : null;

            log.debug("[MAP] Planning iteration {}: score={}, conflicts={}",
                    i, score, conflictCtx != null ? "detected" : "none");

            if (score >= confidenceThreshold && conflictCtx == null) {
                log.debug("[MAP] Plan accepted at iteration {}", i);
                break;
            }
            List<Subgoal> refined = decompose(goal, tools, conflictCtx);
            if (!refined.isEmpty()) best = refined;
        }

        log.debug("[MAP] Injecting plan with {} subgoals into session {}", best.size(), session.getId());
        injectPlan(session, goal, best);
        session.getAttributes().put(PHASE_KEY, "EXECUTE");
        return delegate.plan(session, intent);
    }

    // ── TaskDecomposer ──────────────────────────────────────────────────────────

    private List<Subgoal> decompose(String goal, List<Tool> tools, String conflictCtx) {
        String conflictBlock = conflictCtx != null
                ? labels.getMapResolveConflicts() + "\n" + conflictCtx + "\n\n"
                : "";
        String systemPrompt = promptLoader.load(decomposePrompt, Map.of(
                "tools", formatTools(tools),
                "maxSubtasks", String.valueOf(maxSubtasks),
                "conflictContext", conflictBlock
        ));
        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(labels.getMapGoalPrefix() + " " + goal)
            ))).call().content();
            List<Subgoal> result = parseSubgoals(response);
            if (result.size() > maxSubtasks) result = result.subList(0, maxSubtasks);
            if (!result.isEmpty()) return result;
        } catch (Exception e) {
            log.warn("[MAP] Decomposition failed: {}", e.getMessage());
        }
        return List.of(new Subgoal("1", goal, List.of()));
    }

    // ── Evaluator ────────────────────────────────────────────────────────────────

    private double evaluate(SessionState session, String goal, List<Subgoal> subgoals) {
        String systemPrompt = promptLoader.load(evaluatePrompt);
        try {
            String userMsg = labels.getMapGoalPrefix() + " " + goal
                    + "\n\n" + labels.getMapProposedPlan() + "\n" + formatSubgoalsForPrompt(subgoals);
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userMsg)
            ))).call().content();
            Map<String, Object> result = parseJsonObject(response);
            return ((Number) result.getOrDefault("score", 0.5)).doubleValue();
        } catch (Exception e) {
            log.warn("[MAP] Evaluator failed: {}", e.getMessage());
            return 0.5;
        }
    }

    // ── ConflictMonitor ─────────────────────────────────────────────────────────

    private String checkConflicts(SessionState session, String goal, List<Subgoal> subgoals) {
        String systemPrompt = promptLoader.load(conflictPrompt);
        try {
            String userMsg = labels.getMapGoalPrefix() + " " + goal
                    + "\n\n" + labels.getMapSubgoalsHeader() + "\n" + formatSubgoalsForPrompt(subgoals);
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userMsg)
            ))).call().content();
            Map<String, Object> result = parseJsonObject(response);
            if (Boolean.TRUE.equals(result.get("conflict_detected"))) {
                String description = (String) result.getOrDefault("description", "");
                String resolution = (String) result.getOrDefault("resolution", "");
                log.debug("[MAP] Conflict detected for session {}: {}", session.getId(), description);
                return description + (resolution.isBlank() ? "" : ". " + resolution);
            }
        } catch (Exception e) {
            log.warn("[MAP] ConflictMonitor failed: {}", e.getMessage());
        }
        return null;
    }

    // ── StatePredictor ──────────────────────────────────────────────────────────

    private void predictState(SessionState session, String goal, Subgoal subgoal) {
        String systemPrompt = promptLoader.load(predictPrompt);
        try {
            String userMsg = labels.getMapGoalPrefix() + " " + goal
                    + "\n" + labels.getMapSubgoalLabel() + " " + subgoal.description();
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userMsg)
            ))).call().content();
            Map<String, Object> result = parseJsonObject(response);
            String prediction = (String) result.getOrDefault("prediction", "");
            if (!prediction.isBlank()) {
                session.addMessage(Message.system(labels.getMapStatePredictionTag() + subgoal.id() + ": " + prediction));
            }
        } catch (Exception e) {
            log.warn("[MAP] StatePredictor failed: {}", e.getMessage());
        }
    }

    // ── Plan injection ──────────────────────────────────────────────────────────

    private void injectPlan(SessionState session, String goal, List<Subgoal> subgoals) {
        String subgoalsList = formatSubgoalsForInjection(subgoals);
        String planText = promptLoader.load(executeSystemPrompt, Map.of(
                "goal", goal,
                "subgoals", subgoalsList
        ));
        if (planText.isBlank()) {
            planText = labels.getMapPlanHeader() + " " + goal + "\n\n" + subgoalsList;
        }
        session.addMessage(Message.pinnedUser(planText));
    }

    // ── Formatting helpers ──────────────────────────────────────────────────────

    private String formatTools(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            Map<String, Object> schema = tool.getParameterSchema();
            if (schema != null) {
                Object props = schema.get("properties");
                Object required = schema.get("required");
                if (props instanceof Map<?, ?> propsMap && !propsMap.isEmpty()) {
                    try {
                        sb.append(labels.getMapToolParameters()).append(objectMapper.writeValueAsString(propsMap));
                    } catch (Exception e) {
                        sb.append(labels.getMapToolParameters()).append(propsMap);
                    }
                    if (required instanceof List<?> req && !req.isEmpty()) {
                        sb.append(labels.getMapToolRequired()).append(req).append(")");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String formatSubgoalsForPrompt(List<Subgoal> subgoals) {
        StringBuilder sb = new StringBuilder();
        for (Subgoal sg : subgoals) {
            sb.append(sg.id()).append(". ").append(sg.description());
            if (!sg.dependsOn().isEmpty()) {
                sb.append(labels.getMapDependsOn()).append(String.join(", ", sg.dependsOn())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatSubgoalsForInjection(List<Subgoal> subgoals) {
        StringBuilder sb = new StringBuilder();
        for (Subgoal sg : subgoals) {
            sb.append(sg.id()).append(". ").append(sg.description());
            if (sg.dependsOn().isEmpty()) {
                sb.append(labels.getMapIndependent());
            } else {
                sb.append(labels.getMapDependsOn()).append(String.join(", ", sg.dependsOn())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── Parsers ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Subgoal> parseSubgoals(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            List<Map<String, Object>> raw = objectMapper.readValue(cleaned, new TypeReference<>() {});
            List<Subgoal> result = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                String id = String.valueOf(item.getOrDefault("id", String.valueOf(result.size() + 1)));
                String description = String.valueOf(item.getOrDefault("description", ""));
                Object deps = item.get("depends_on");
                List<String> dependsOn = deps instanceof List<?> list
                        ? list.stream().map(Object::toString).toList()
                        : List.of();
                if (!description.isBlank()) result.add(new Subgoal(id, description, dependsOn));
            }
            return result;
        } catch (Exception e) {
            log.warn("[MAP] Failed to parse subgoals: {}", e.getMessage());
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
            return objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
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

    // ── Inner model ─────────────────────────────────────────────────────────────

    private record Subgoal(String id, String description, List<String> dependsOn) {}
}
