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
import com.intentreactor.api.StepType;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.MessageMarkers;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.config.StrategySessionKeys;
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
 * Hierarchical Task Planning (HTP) planner: decomposes the goal into a hyper-tree of sub-goals
 * with constraints and dependencies, plans each node with a mini execution plan, executes steps
 * sequentially, and optionally refines failed nodes.
 * <p>
 * Prompt files configured via intent-reactor.planning.strategies.prompts.*
 * <p>
 * Activate with: intent-reactor.planning.strategy: htp
 */
public class HTPPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(HTPPlanner.class);

    private static final String PHASE_KEY            = StrategySessionKeys.HTP_PHASE;
    private static final String SUBGOALS_KEY          = StrategySessionKeys.HTP_SUBGOALS;
    private static final String SUBGOAL_IDX_KEY       = StrategySessionKeys.HTP_SUBGOAL_IDX;
    private static final String STEPS_KEY             = StrategySessionKeys.HTP_STEPS;
    private static final String STEP_IDX_KEY          = StrategySessionKeys.HTP_STEP_IDX;
    private static final String RESULTS_KEY           = StrategySessionKeys.HTP_RESULTS;
    private static final String REFINEMENT_COUNT_KEY  = StrategySessionKeys.HTP_REFINEMENT_COUNT;
    private static final String NODE_MSG_START_KEY    = StrategySessionKeys.HTP_NODE_MSG_START;

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int maxSubgoals;
    private final int maxStepsPerNode;
    private final boolean refinementEnabled;
    private final int maxRefinementRetries;
    private final boolean autonomous;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String decomposePromptPath;
    private final String planNodePromptPath;
    private final String refinePromptPath;
    private final String synthesizePromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public HTPPlanner(ChatClient chatClient, ToolProvider toolProvider,
                      ObjectMapper objectMapper, StrategiesProperties props,
                      IntentReactorProperties intentReactorProperties) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        StrategiesProperties.HtpConfig cfg = props.getHtp();
        this.maxSubgoals = cfg.getMaxSubgoals();
        this.maxStepsPerNode = cfg.getMaxStepsPerNode();
        this.refinementEnabled = cfg.isRefinementEnabled();
        this.maxRefinementRetries = cfg.getMaxRefinementRetries();
        this.autonomous = intentReactorProperties.getPlanning().isAutonomous();
        this.decomposePromptPath = props.getPrompts().getHtpDecompose();
        this.planNodePromptPath = props.getPrompts().getHtpPlanNode();
        this.refinePromptPath = props.getPrompts().getHtpRefine();
        this.synthesizePromptPath = props.getPrompts().getHtpSynthesize();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "DECOMPOSE");
        String goal = getGoal(session);

        return switch (phase) {
            case "DECOMPOSE" -> decompose(session, goal);
            case "PLAN_NODE" -> planNode(session, goal, "");
            case "EXECUTE_STEPS" -> executeNextStep(session, goal);
            case "SYNTHESIZE" -> synthesize(session, goal);
            default -> decompose(session, goal);
        };
    }

    private Plan decompose(SessionState session, String goal) {
        List<Tool> tools = toolProvider.getAvailableTools(session);
        String toolsList = tools.stream()
                .map(t -> t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));

        try {
            String system = promptLoader.load(decomposePromptPath,
                    Map.of("maxSubgoals", maxSubgoals, "tools", toolsList));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(labels.getTask() + goal)
            ))).call().content();

            List<Map<String, Object>> subgoals = parseList(response);
            if (subgoals.isEmpty()) {
                return new SimplePlan(List.of(SimplePlanStep.fail("HTP: Could not decompose goal: " + goal)));
            }
            if (subgoals.size() > maxSubgoals) subgoals = subgoals.subList(0, maxSubgoals);

            session.getAttributes().put(SUBGOALS_KEY, subgoals);
            session.getAttributes().put(SUBGOAL_IDX_KEY, 0);
            session.getAttributes().put(RESULTS_KEY, new LinkedHashMap<>());
            session.getAttributes().put(PHASE_KEY, "PLAN_NODE");

            log.debug("[HTP] Decomposed into {} sub-goals for session {}", subgoals.size(), session.getId());
            return planNode(session, goal, "");

        } catch (Exception e) {
            log.warn("[HTP] Decomposition failed: {}", e.getMessage());
            return new SimplePlan(List.of(SimplePlanStep.fail("HTP decomposition failed: " + e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private Plan planNode(SessionState session, String goal, String refinementContext) {
        List<Map<String, Object>> subgoals =
                (List<Map<String, Object>>) session.getAttributes().get(SUBGOALS_KEY);
        int idx = (int) session.getAttributes().getOrDefault(SUBGOAL_IDX_KEY, 0);

        if (idx >= subgoals.size()) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }

        Map<String, Object> subgoal = subgoals.get(idx);
        String subgoalDesc = (String) subgoal.getOrDefault("description", "Sub-goal " + (idx + 1));
        String constraints = (String) subgoal.getOrDefault("constraints", "");

        List<Tool> tools = toolProvider.getAvailableTools(session);
        String toolsList = tools.stream()
                .map(t -> t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));

        try {
            String system = promptLoader.load(planNodePromptPath, Map.of(
                    "subgoal", subgoalDesc,
                    "constraints", constraints.isBlank() ? "None" : constraints,
                    "refinementContext", refinementContext.isBlank() ? "None" : refinementContext,
                    "tools", toolsList,
                    "maxSteps", maxStepsPerNode
            ));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(labels.getTask() + goal)
            ))).call().content();

            List<Map<String, Object>> steps = parseList(response);
            if (steps.isEmpty()) {
                recordSubgoalResult(session, idx, "Subgoal skipped: empty plan");
                session.getAttributes().put(REFINEMENT_COUNT_KEY, 0);
                return planNode(session, goal, "");
            }
            if (steps.size() > maxStepsPerNode) steps = steps.subList(0, maxStepsPerNode);

            session.getAttributes().put(STEPS_KEY, steps);
            session.getAttributes().put(STEP_IDX_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "EXECUTE_STEPS");
            session.getAttributes().put(REFINEMENT_COUNT_KEY, 0);
            session.getAttributes().put(NODE_MSG_START_KEY, session.getMessages().size());

            log.debug("[HTP] Planned {} steps for sub-goal {} for session {}", steps.size(), idx + 1, session.getId());
            return executeNextStep(session, goal);

        } catch (Exception e) {
            log.warn("[HTP] Node planning failed: {}", e.getMessage());
            recordSubgoalResult(session, idx, "Subgoal failed: " + e.getMessage());
            return planNode(session, goal, "");
        }
    }

    @SuppressWarnings("unchecked")
    private Plan executeNextStep(SessionState session, String goal) {
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) session.getAttributes().get(STEPS_KEY);
        int stepIdx = (int) session.getAttributes().getOrDefault(STEP_IDX_KEY, 0);

        List<Message> messages = session.getMessages();
        if (stepIdx > 0 && !messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last.getRole() == Message.Role.SYSTEM) {
                String content = last.getContent();
                boolean failed = content != null && content.startsWith(MessageMarkers.TOOL_ERROR);
                if (failed && refinementEnabled) {
                    return tryRefine(session, goal, content);
                }
            }
        }

        if (steps == null || stepIdx >= steps.size()) {
            collectSubgoalResult(session, goal);
            session.getAttributes().put(PHASE_KEY, "PLAN_NODE");
            return planNode(session, goal, "");
        }

        Map<String, Object> step = steps.get(stepIdx);
        String actionDesc = (String) step.getOrDefault("action", "Step " + (stepIdx + 1));
        String toolName = (String) step.get("toolName");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = step.get("parameters") instanceof Map
                ? (Map<String, Object>) step.get("parameters") : Map.of();

        session.getAttributes().put(STEP_IDX_KEY, stepIdx + 1);

        if (toolName == null || toolName.isBlank()) {
            return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null, actionDesc, false)));
        }

        List<Tool> tools = toolProvider.getAvailableTools(session);
        boolean toolExists = tools.stream().anyMatch(t -> t.getName().equals(toolName));
        if (!toolExists) {
            return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null, actionDesc, false)));
        }

        boolean isRisky = tools.stream().anyMatch(t -> t.getName().equals(toolName) && t.isRisky());
        boolean needsConfirmation = isRisky && !autonomous;
        Action action = new SimpleAction(toolName, parameters);
        log.debug("[HTP] Executing step {}/{} for session {}", stepIdx + 1, steps.size(), session.getId());
        return new SimplePlan(List.of(SimplePlanStep.act(action, actionDesc, needsConfirmation)));
    }

    @SuppressWarnings("unchecked")
    private Plan tryRefine(SessionState session, String goal, String errorContext) {
        int refineCount = (int) session.getAttributes().getOrDefault(REFINEMENT_COUNT_KEY, 0);
        if (refineCount >= maxRefinementRetries) {
            log.debug("[HTP] Max refinements reached, moving to next sub-goal for session {}", session.getId());
            collectSubgoalResult(session, goal);
            session.getAttributes().put(PHASE_KEY, "PLAN_NODE");
            return planNode(session, goal, "");
        }

        List<Map<String, Object>> subgoals =
                (List<Map<String, Object>>) session.getAttributes().get(SUBGOALS_KEY);
        int idx = (int) session.getAttributes().getOrDefault(SUBGOAL_IDX_KEY, 0);
        Map<String, Object> subgoal = subgoals.get(idx);
        String subgoalDesc = (String) subgoal.getOrDefault("description", "Sub-goal " + (idx + 1));
        String constraints = (String) subgoal.getOrDefault("constraints", "");

        try {
            String system = promptLoader.load(refinePromptPath, Map.of(
                    "subgoal", subgoalDesc,
                    "constraints", constraints.isBlank() ? "None" : constraints
            ));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(labels.getTask() + goal + "\n\nError context: " + errorContext)
            ))).call().content();

            List<Map<String, Object>> newSteps = parseList(response);
            if (!newSteps.isEmpty()) {
                if (newSteps.size() > maxStepsPerNode) newSteps = newSteps.subList(0, maxStepsPerNode);
                session.getAttributes().put(STEPS_KEY, newSteps);
                session.getAttributes().put(STEP_IDX_KEY, 0);
                session.getAttributes().put(REFINEMENT_COUNT_KEY, refineCount + 1);
                log.debug("[HTP] Refined plan with {} steps for session {}", newSteps.size(), session.getId());
                return executeNextStep(session, goal);
            }
        } catch (Exception e) {
            log.warn("[HTP] Refinement failed: {}", e.getMessage());
        }

        collectSubgoalResult(session, goal);
        session.getAttributes().put(PHASE_KEY, "PLAN_NODE");
        return planNode(session, goal, "");
    }

    private void collectSubgoalResult(SessionState session, String goal) {
        int idx = (int) session.getAttributes().getOrDefault(SUBGOAL_IDX_KEY, 0);
        int start = (int) session.getAttributes().getOrDefault(NODE_MSG_START_KEY, 0);
        List<Message> messages = session.getMessages();
        int from = Math.min(start, messages.size());

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.getRole() == Message.Role.SYSTEM && m.getContent() != null
                    && (m.getContent().startsWith(MessageMarkers.TOOL_RESULT)
                        || m.getContent().startsWith(MessageMarkers.TOOL_ERROR))) {
                sb.append(m.getContent()).append("\n");
            }
        }
        String result = sb.length() > 0 ? sb.toString().trim() : "Completed";
        recordSubgoalResult(session, idx, result);
    }

    @SuppressWarnings("unchecked")
    private void recordSubgoalResult(SessionState session, int idx, String text) {
        Map<String, String> results =
                (Map<String, String>) session.getAttributes().computeIfAbsent(RESULTS_KEY, k -> new LinkedHashMap<>());
        results.put("subgoal-" + (idx + 1), text);
        session.getAttributes().put(RESULTS_KEY, results);
        session.getAttributes().put(SUBGOAL_IDX_KEY, idx + 1);
    }

    @SuppressWarnings("unchecked")
    private Plan synthesize(SessionState session, String goal) {
        Map<String, String> results =
                (Map<String, String>) session.getAttributes().getOrDefault(RESULTS_KEY, Map.of());
        StringBuilder combined = new StringBuilder();
        results.forEach((k, v) -> combined.append(k).append(": ").append(v).append("\n"));

        try {
            String system = promptLoader.load(synthesizePromptPath, Map.of());
            String userMsg = labels.getTask() + goal + "\n\n" + combined;

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(userMsg)
            ))).call().content();

            return new SimplePlan(List.of(SimplePlanStep.done(response)));
        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done(combined.toString())));
        }
    }

    private List<Map<String, Object>> parseList(String response) {
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

    private String getGoal(SessionState session) {
        if (session.getPlanState() != null) {
            String g = session.getPlanState().getGoalDescription();
            if (g != null && !g.isBlank()) return g;
        }
        List<Message> msgs = session.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == Message.Role.USER) return msgs.get(i).getContent();
        }
        return "unknown";
    }
}
