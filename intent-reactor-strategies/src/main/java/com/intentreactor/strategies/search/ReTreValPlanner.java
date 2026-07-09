package com.intentreactor.strategies.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.SimulatableTool;
import com.intentreactor.api.StepType;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.api.ToolResult;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ReTreVal (Reasoning Tree with Validation): explores a beam-width bounded reasoning tree where each
 * candidate step is scored by self-evaluation and an independent critic before being accepted.
 * Failed branches trigger typed backtracking; successful patterns are accumulated in session memory
 * to guide future expansions.
 *
 * <p>Algorithm: EXPAND (beam search with dual validation + simulate) → SYNTHESIZE (best path → answer).
 *
 * <p>Session attributes used:
 * <ul>
 *   <li>{@code retreval_tree} — JSON-serialized {@link RetrevalTree}</li>
 *   <li>{@code retreval_phase} — "EXPAND" | "SYNTHESIZE"</li>
 *   <li>{@code retreval_frontier} — {@code List<String>} of node IDs ready for expansion</li>
 *   <li>{@code retreval_cur_node} — ID of the node currently being executed (ACT in progress)</li>
 *   <li>{@code retreval_patterns} — JSON-serialized {@code List<RetrevalPattern>}</li>
 *   <li>{@code retreval_backtrack} — {@code [BACKTRACK: type] description} string injected into next expand</li>
 * </ul>
 *
 * <p>Activate with: {@code intent-reactor.planning.strategy: retreval}
 */
public class ReTreValPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(ReTreValPlanner.class);

    private static final String TREE_KEY = StrategySessionKeys.RETREVAL_TREE;
    private static final String PHASE_KEY = StrategySessionKeys.RETREVAL_PHASE;
    private static final String FRONTIER_KEY = StrategySessionKeys.RETREVAL_FRONTIER;
    private static final String CUR_NODE_KEY = StrategySessionKeys.RETREVAL_CUR_NODE;
    private static final String PATTERNS_KEY = StrategySessionKeys.RETREVAL_PATTERNS;
    private static final String BACKTRACK_KEY = StrategySessionKeys.RETREVAL_BACKTRACK;
    private static final String GOAL_KEY = StrategySessionKeys.RETREVAL_GOAL;

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader = new PromptLoader();

    private final int maxTreeDepth;
    private final int candidatesPerStep;
    private final int beamWidth;
    private final double validationThreshold;
    private final double finalThreshold;
    private final boolean useExternalCritic;
    private final boolean memoryEnabled;
    private final int maxMemories;

    private final String expandPromptPath;
    private final String selfScorePromptPath;
    private final String criticScorePromptPath;
    private final String synthesizePromptPath;
    private final String simulatePromptPath;
    private final String backtrackPromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public ReTreValPlanner(ChatClient chatClient, ToolProvider toolProvider,
                           ObjectMapper objectMapper, StrategiesProperties props) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        StrategiesProperties.RetrevalConfig cfg = props.getRetreval();
        this.maxTreeDepth = cfg.getMaxTreeDepth();
        this.candidatesPerStep = cfg.getCandidatesPerStep();
        this.beamWidth = cfg.getBeamWidth();
        this.validationThreshold = cfg.getValidationThreshold();
        this.finalThreshold = cfg.getFinalThreshold();
        this.useExternalCritic = cfg.isUseExternalCritic();
        this.memoryEnabled = cfg.isMemoryEnabled();
        this.maxMemories = cfg.getMaxMemories();
        StrategiesProperties.PromptsConfig p = props.getPrompts();
        this.expandPromptPath = p.getRetrevalExpand();
        this.selfScorePromptPath = p.getRetrevalSelfScore();
        this.criticScorePromptPath = p.getRetrevalCriticScore();
        this.synthesizePromptPath = p.getRetrevalSynthesize();
        this.simulatePromptPath = p.getRetrevalSimulate();
        this.backtrackPromptPath = p.getRetrevalBacktrack();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String goal = getGoal(intent, session);

        String storedGoal = (String) session.getAttributes().getOrDefault(GOAL_KEY, "");
        if (!storedGoal.equals(goal)) {
            // Goal changed within the session — reset search state. retreval_patterns is
            // intentionally preserved: it is cross-goal memory of successful/failed reasoning steps.
            session.getAttributes().remove(TREE_KEY);
            session.getAttributes().remove(FRONTIER_KEY);
            session.getAttributes().remove(CUR_NODE_KEY);
            session.getAttributes().remove(BACKTRACK_KEY);
            session.getAttributes().put(PHASE_KEY, "EXPAND");
            session.getAttributes().put(GOAL_KEY, goal);
        }

        RetrevalTree tree = loadTree(session);
        if (tree == null) {
            tree = RetrevalTree.withRoot(goal);
            List<String> frontier = new ArrayList<>();
            frontier.add(tree.getRootId());
            session.getAttributes().put(FRONTIER_KEY, frontier);
            saveTree(session, tree);
        }

        collectLastResult(session, tree);

        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "EXPAND");
        if ("SYNTHESIZE".equals(phase)) {
            return synthesize(session, goal, tree);
        }
        return expand(session, goal, tree, 0);
    }

    /**
     * If the previous step was an ACT, the execution engine has added a SYSTEM message with the
     * tool result. Collect it into the current node's observation and push that node to the frontier
     * so it becomes the parent for the next expansion.
     */
    @SuppressWarnings("unchecked")
    private void collectLastResult(SessionState session, RetrevalTree tree) {
        List<Message> msgs = session.getMessages();
        if (msgs.isEmpty()) return;
        Message last = msgs.get(msgs.size() - 1);
        if (last.getRole() != Message.Role.SYSTEM) return;

        String curNodeId = (String) session.getAttributes().remove(CUR_NODE_KEY);
        if (curNodeId == null) return;

        RetrevalNode node = tree.getNodes().get(curNodeId);
        if (node != null && node.getToolObservation() == null) {
            node.setToolObservation(last.getContent());
            List<String> frontier = (List<String>) session.getAttributes()
                    .computeIfAbsent(FRONTIER_KEY, k -> new ArrayList<>());
            maintainFrontier(frontier, curNodeId, tree, beamWidth);
            session.getAttributes().put(FRONTIER_KEY, frontier);
            saveTree(session, tree);
        }
    }

    /**
     * Keeps the frontier as the top-{@code beamWidth} node IDs by (self+critic)/2 score.
     */
    private void maintainFrontier(List<String> frontier, String candidateId, RetrevalTree tree, int beamWidth) {
        if (candidateId != null && !frontier.contains(candidateId)) frontier.add(candidateId);
        frontier.sort(Comparator.comparingDouble((String id) -> frontierScore(tree, id)).reversed());
        while (frontier.size() > beamWidth) frontier.remove(frontier.size() - 1);
    }

    private double frontierScore(RetrevalTree tree, String id) {
        RetrevalNode n = tree.getNodes().get(id);
        return n != null ? (n.getSelfScore() + n.getCriticScore()) / 2.0 : Double.NEGATIVE_INFINITY;
    }

    @SuppressWarnings("unchecked")
    private Plan expand(SessionState session, String goal, RetrevalTree tree, int backtrackDepth) {
        if (backtrackDepth > 3) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal, tree);
        }

        List<String> frontier = (List<String>) session.getAttributes()
                .computeIfAbsent(FRONTIER_KEY, k -> new ArrayList<>());

        if (frontier.isEmpty()) {
            log.debug("[ReTreVal] Frontier empty, synthesizing (session={})", session.getId());
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal, tree);
        }

        String parentId = pickBestFrontierNode(frontier, tree);
        frontier.remove(parentId);
        session.getAttributes().put(FRONTIER_KEY, frontier);

        RetrevalNode parentNode = tree.getNodes().get(parentId);
        int depth = parentNode != null ? parentNode.getDepth() : 0;

        if (depth >= maxTreeDepth) {
            log.debug("[ReTreVal] Max depth {} reached, synthesizing (session={})", maxTreeDepth, session.getId());
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal, tree);
        }

        List<Tool> tools = toolProvider.getAvailableTools(session);
        String memoryContext = buildMemoryContext(session, tree);
        String backtrackMsg = (String) session.getAttributes().getOrDefault(BACKTRACK_KEY, "");
        if (!backtrackMsg.isBlank()) {
            memoryContext += "\n\n" + backtrackMsg;
        }

        String toolsList = tools.stream()
                .map(t -> t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));
        String nodeContext = parentNode != null ? parentNode.getContent() : goal;
        if (parentNode != null && parentNode.getToolObservation() != null) {
            nodeContext += "\nTool result: " + parentNode.getToolObservation();
        }

        try {
            String expandSystem = promptLoader.load(expandPromptPath,
                    Map.of("K", candidatesPerStep, "memory", memoryContext, "tools", toolsList));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(expandSystem),
                    new UserMessage(labels.getTask() + goal + labels.getCurrentReasoning() + nodeContext)
            ))).call().content();

            List<Map<String, Object>> rawCandidates = parseCandidates(response);
            if (rawCandidates.isEmpty()) {
                session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
                return synthesize(session, goal, tree);
            }

            List<RetrevalNode> childNodes = new ArrayList<>();
            for (Map<String, Object> cand : rawCandidates) {
                String reasoning = (String) cand.getOrDefault("reasoning", "");
                String toolName = (String) cand.get("toolName");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = cand.get("parameters") instanceof Map
                        ? (Map<String, Object>) cand.get("parameters") : Map.of();
                String candType = detectType(cand, tools);

                RetrevalNode node = new RetrevalNode(reasoning, parentId, depth + 1);
                node.setToolName(toolName);
                node.setToolParameters(params);
                tree.getNodes().put(node.getId(), node);
                if (parentNode != null) parentNode.getChildIds().add(node.getId());

                String predictedResult = "";
                if ("ACT".equals(candType) && toolName != null) {
                    predictedResult = simulate(toolName, params, nodeContext, tools, session);
                }
                String scoringContext = reasoning
                        + (predictedResult.isBlank() ? "" : "\nPredicted result: " + predictedResult);

                double selfScore = scoreCandidate(scoringContext, goal, selfScorePromptPath);
                double criticScore = useExternalCritic
                        ? scoreCandidate(scoringContext, goal, criticScorePromptPath) : selfScore;
                double avg = (selfScore + criticScore) / 2.0;
                node.setSelfScore(selfScore);
                node.setCriticScore(criticScore);

                log.debug("[ReTreVal] Candidate type={} avg={} depth={} session={}",
                        candType, avg, depth + 1, session.getId());

                if ("DONE".equals(candType) && avg >= finalThreshold) {
                    node.setState("DONE");
                    addPattern(session, new RetrevalPattern("SUCCESS", reasoning, null, avg));
                } else if (avg >= validationThreshold) {
                    node.setState("VALIDATED");
                    if (avg >= finalThreshold) {
                        addPattern(session, new RetrevalPattern("SUCCESS", reasoning, null, avg));
                    }
                } else {
                    node.setState("FAILED");
                    node.setFailureContext("avg=" + avg + " threshold=" + validationThreshold);
                }
                childNodes.add(node);
            }

            saveTree(session, tree);

            // Immediate synthesis if a DONE node was validated
            Optional<RetrevalNode> doneOpt = childNodes.stream()
                    .filter(n -> "DONE".equals(n.getState())).findFirst();
            if (doneOpt.isPresent()) {
                tree.setCurrentNodeId(doneOpt.get().getId());
                session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
                saveTree(session, tree);
                return synthesize(session, goal, tree);
            }

            List<RetrevalNode> validated = childNodes.stream()
                    .filter(n -> "VALIDATED".equals(n.getState()))
                    .sorted(Comparator.comparingDouble(n -> -((n.getSelfScore() + n.getCriticScore()) / 2)))
                    .collect(Collectors.toList());

            if (validated.isEmpty()) {
                List<RetrevalNode> failed = childNodes.stream()
                        .filter(n -> "FAILED".equals(n.getState()))
                        .collect(Collectors.toList());
                String backtrackContext = buildBacktrackContext(failed);
                session.getAttributes().put(BACKTRACK_KEY, backtrackContext);
                for (RetrevalNode fn : failed) {
                    String ft = fn.getFailureType() != null ? fn.getFailureType() : "SCORE_TOO_LOW";
                    addPattern(session, new RetrevalPattern("FAILURE", fn.getContent(), ft,
                            (fn.getSelfScore() + fn.getCriticScore()) / 2));
                }
                log.debug("[ReTreVal] All candidates failed at depth {}, backtracking (session={})",
                        depth + 1, session.getId());

                if (parentNode != null && parentNode.getParentId() != null) {
                    String grandparentId = parentNode.getParentId();
                    maintainFrontier(frontier, grandparentId, tree, beamWidth);
                    session.getAttributes().put(FRONTIER_KEY, frontier);
                }
                saveTree(session, tree);
                return expand(session, goal, tree, backtrackDepth + 1);
            }

            // Add remaining validated siblings to frontier (beam management, score-based top-K)
            List<RetrevalNode> siblings = validated.size() > 1
                    ? validated.subList(1, validated.size()) : List.of();
            for (RetrevalNode sib : siblings) {
                maintainFrontier(frontier, sib.getId(), tree, beamWidth);
            }
            session.getAttributes().put(FRONTIER_KEY, frontier);
            session.getAttributes().remove(BACKTRACK_KEY);

            RetrevalNode bestNode = validated.get(0);
            tree.setCurrentNodeId(bestNode.getId());
            saveTree(session, tree);

            String bestToolName = bestNode.getToolName();
            boolean toolExists = bestToolName != null
                    && tools.stream().anyMatch(t -> t.getName().equals(bestToolName));

            if (!toolExists) {
                // REASON step: no tool result follows, so add node to frontier directly
                // so the next plan() call will expand its children
                maintainFrontier(frontier, bestNode.getId(), tree, beamWidth);
                session.getAttributes().put(FRONTIER_KEY, frontier);
                session.getAttributes().remove(CUR_NODE_KEY);
                return new SimplePlan(List.of(
                        new SimplePlanStep(StepType.REASON, null, bestNode.getContent(), false)));
            }

            // ACT step: set CUR_NODE_KEY so collectLastResult picks up the tool result
            session.getAttributes().put(CUR_NODE_KEY, bestNode.getId());
            boolean isRisky = tools.stream()
                    .anyMatch(t -> t.getName().equals(bestToolName) && t.isRisky());
            return new SimplePlan(List.of(
                    SimplePlanStep.act(new SimpleAction(bestToolName, bestNode.getToolParameters()),
                            bestNode.getContent(), isRisky)));

        } catch (Exception e) {
            log.warn("[ReTreVal] Expand failed: {}", e.getMessage());
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal, tree);
        }
    }

    private Plan synthesize(SessionState session, String goal, RetrevalTree tree) {
        List<RetrevalNode> path = tree.getValidatedPath();
        String pathStr = path.stream()
                .filter(n -> !n.getId().equals(tree.getRootId()))
                .map(n -> n.getContent()
                        + (n.getToolObservation() != null && !n.getToolObservation().isBlank()
                        ? "\n→ " + n.getToolObservation() : ""))
                .collect(Collectors.joining("\n---\n"));
        if (pathStr.isBlank()) pathStr = "No reasoning paths collected.";

        try {
            String system = promptLoader.load(synthesizePromptPath, Map.of());
            String userMsg = labels.getTask() + goal + "\n\n" + pathStr;
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(userMsg)
            ))).call().content();
            return new SimplePlan(List.of(SimplePlanStep.done(response)));
        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done(pathStr)));
        }
    }

    private String simulate(String toolName, Map<String, Object> params,
                            String context, List<Tool> tools, SessionState session) {
        Tool tool = tools.stream().filter(t -> t.getName().equals(toolName)).findFirst().orElse(null);
        if (tool == null) return "";

        if (tool instanceof SimulatableTool sim) {
            try {
                ToolResult r = sim.simulate(new ToolInput(params, session.getId()));
                return r.isSuccess() ? String.valueOf(r.getData()) : r.getErrorMessage();
            } catch (Exception e) {
                log.debug("[ReTreVal] simulate() failed for {}: {}", toolName, e.getMessage());
            }
        }

        try {
            String paramsStr = objectMapper.writeValueAsString(params);
            String system = promptLoader.load(simulatePromptPath,
                    Map.of("toolName", toolName, "parameters", paramsStr, "context", context));
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage("Predict the tool output.")
            ))).call().content();
            String cleaned = stripMarkdownFences(response.strip());
            int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
            if (s >= 0 && e > s) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.readValue(cleaned.substring(s, e + 1), Map.class);
                return String.valueOf(map.getOrDefault("predicted_result", ""));
            }
        } catch (Exception e) {
            log.debug("[ReTreVal] LLM simulate failed for {}: {}", toolName, e.getMessage());
        }
        return "";
    }

    private double scoreCandidate(String reasoning, String goal, String promptPath) {
        try {
            String system = promptLoader.load(promptPath, Map.of());
            String userMsg = labels.getTask() + goal + labels.getCurrentReasoning() + reasoning;
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(userMsg)
            ))).call().content();
            String cleaned = stripMarkdownFences(response.strip());
            int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
            if (s >= 0 && e > s) cleaned = cleaned.substring(s, e + 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
            return ((Number) map.getOrDefault("score", 0.5)).doubleValue();
        } catch (Exception e) {
            return 0.5;
        }
    }

    private String buildBacktrackContext(List<RetrevalNode> failed) {
        if (failed.isEmpty()) return "[BACKTRACK: SCORE_TOO_LOW] All candidates scored below threshold.";

        RetrevalNode worst = failed.stream()
                .min(Comparator.comparingDouble(n -> (n.getSelfScore() + n.getCriticScore()) / 2))
                .orElse(failed.get(0));

        try {
            String system = promptLoader.load(backtrackPromptPath, Map.of(
                    "reasoning", worst.getContent(),
                    "selfScore", worst.getSelfScore(),
                    "criticScore", worst.getCriticScore()));
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage("Classify the failure.")
            ))).call().content();
            String cleaned = stripMarkdownFences(response.strip());
            int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
            if (s >= 0 && e > s) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.readValue(cleaned.substring(s, e + 1), Map.class);
                String ft = String.valueOf(map.getOrDefault("failureType", "SCORE_TOO_LOW"));
                String desc = String.valueOf(map.getOrDefault("description", ""));
                String avoidance = String.valueOf(map.getOrDefault("avoidance", ""));
                worst.setFailureType(ft);
                for (RetrevalNode fn : failed) if (fn.getFailureType() == null) fn.setFailureType(ft);
                return "[BACKTRACK: " + ft + "] " + desc
                        + (avoidance.isBlank() ? "" : "\nAvoid: " + avoidance);
            }
        } catch (Exception e) {
            log.debug("[ReTreVal] Backtrack LLM failed: {}", e.getMessage());
        }
        return "[BACKTRACK: SCORE_TOO_LOW] All candidates scored below threshold.";
    }

    @SuppressWarnings("unchecked")
    private String buildMemoryContext(SessionState session, RetrevalTree tree) {
        List<RetrevalNode> path = tree.getValidatedPath();
        String pathStr = path.stream()
                .filter(n -> "VALIDATED".equals(n.getState()) || "DONE".equals(n.getState()))
                .map(n -> n.getContent()
                        + (n.getToolObservation() != null ? " → " + n.getToolObservation() : ""))
                .collect(Collectors.joining("\n"));

        if (memoryEnabled) {
            try {
                String json = (String) session.getAttributes().getOrDefault(PATTERNS_KEY, "[]");
                List<RetrevalPattern> patterns = objectMapper.readValue(json,
                        new TypeReference<List<RetrevalPattern>>() {
                        });
                String successStr = patterns.stream()
                        .filter(p -> "SUCCESS".equals(p.getType()))
                        .map(p -> "✓ " + p.getStepContent())
                        .collect(Collectors.joining("\n"));
                String failureStr = patterns.stream()
                        .filter(p -> "FAILURE".equals(p.getType()))
                        .map(p -> "✗ [" + p.getFailureType() + "] " + p.getStepContent())
                        .collect(Collectors.joining("\n"));
                if (!successStr.isBlank()) pathStr += "\n\nSuccessful patterns:\n" + successStr;
                if (!failureStr.isBlank()) pathStr += "\n\nFailed patterns to avoid:\n" + failureStr;
            } catch (Exception ignored) {
            }
        }

        return pathStr.isBlank() ? "No context yet." : pathStr;
    }

    @SuppressWarnings("unchecked")
    private void addPattern(SessionState session, RetrevalPattern pattern) {
        if (!memoryEnabled) return;
        try {
            String json = (String) session.getAttributes().getOrDefault(PATTERNS_KEY, "[]");
            List<RetrevalPattern> patterns = new ArrayList<>(objectMapper.readValue(json,
                    new TypeReference<List<RetrevalPattern>>() {
                    }));
            patterns.add(pattern);
            while (patterns.size() > maxMemories) patterns.remove(0);
            session.getAttributes().put(PATTERNS_KEY, objectMapper.writeValueAsString(patterns));
        } catch (Exception e) {
            log.debug("[ReTreVal] Failed to save pattern: {}", e.getMessage());
        }
    }

    private String pickBestFrontierNode(List<String> frontier, RetrevalTree tree) {
        return frontier.stream()
                .filter(id -> tree.getNodes().containsKey(id))
                .max(Comparator.comparingDouble(id -> {
                    RetrevalNode n = tree.getNodes().get(id);
                    return n != null ? (n.getSelfScore() + n.getCriticScore()) / 2.0 : 0.0;
                }))
                .orElse(frontier.get(0));
    }

    private String detectType(Map<String, Object> candidate, List<Tool> tools) {
        String type = (String) candidate.getOrDefault("type", "");
        if ("DONE".equalsIgnoreCase(type)) return "DONE";
        String toolName = (String) candidate.get("toolName");
        if (toolName != null && !toolName.isBlank()
                && tools.stream().anyMatch(t -> t.getName().equals(toolName))) {
            return "ACT";
        }
        return "REASON";
    }

    private RetrevalTree loadTree(SessionState session) {
        Object val = session.getAttributes().get(TREE_KEY);
        if (val == null) return null;
        try {
            String json = val instanceof String s ? s : objectMapper.writeValueAsString(val);
            return objectMapper.readValue(json, RetrevalTree.class);
        } catch (Exception e) {
            log.warn("[ReTreVal] Failed to load tree: {}", e.getMessage());
            return null;
        }
    }

    private void saveTree(SessionState session, RetrevalTree tree) {
        try {
            session.getAttributes().put(TREE_KEY, objectMapper.writeValueAsString(tree));
        } catch (Exception e) {
            log.warn("[ReTreVal] Failed to save tree: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> parseCandidates(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('['), end = cleaned.lastIndexOf(']');
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

    private String getGoal(IntentAnalysisResult intent, SessionState session) {
        String userContent = session.getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.USER)
                .reduce((a, b) -> b)
                .map(Message::getContent)
                .orElse(null);
        if (userContent != null && !userContent.isBlank()) {
            return userContent;
        }
        if (intent != null && !intent.getIntents().isEmpty()) {
            return intent.getIntents().get(0).getName();
        }
        return "unknown";
    }
}
