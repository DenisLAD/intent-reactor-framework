package com.intentreactor.strategies.search;

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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tree-of-Thoughts (ToT) planner: explores a tree of intermediate reasoning steps using
 * BFS, DFS, or beam search. At each step, generates multiple thought candidates and evaluates
 * each via LLM before selecting the best to continue.
 * <p>
 * Prompt files configured via intent-reactor.planning.strategies.prompts.*
 * <p>
 * Stored in session.attributes:
 * tot_tree : ThoughtGraph (serialized as JSON)
 * <p>
 * Activate with: intent-reactor.planning.strategy: tot
 */
public class TreeOfThoughtsPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(TreeOfThoughtsPlanner.class);
    private static final String TREE_KEY = StrategySessionKeys.TOT_TREE;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String searchAlgorithm;
    private final int beamWidth;
    private final int thoughtsPerStep;
    private final int maxDepth;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String generatePromptPath;
    private final String evaluatePromptPath;
    private final String synthesizePromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public TreeOfThoughtsPlanner(ChatClient chatClient, ObjectMapper objectMapper,
                                 StrategiesProperties props) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        StrategiesProperties.TotConfig cfg = props.getTot();
        this.searchAlgorithm = cfg.getSearchAlgorithm();
        this.beamWidth = cfg.getBeamWidth();
        this.thoughtsPerStep = cfg.getThoughtsPerStep();
        this.maxDepth = cfg.getMaxDepth();
        this.generatePromptPath = props.getPrompts().getTotGenerate();
        this.evaluatePromptPath = props.getPrompts().getTotEvaluate();
        this.synthesizePromptPath = props.getPrompts().getTotSynthesize();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String goal = getGoal(session);
        ThoughtGraph tree = loadOrCreateTree(session, goal);

        List<ThoughtNode> frontier = selectFrontier(tree);
        if (frontier.isEmpty()) {
            ThoughtNode best = tree.bestNode().orElse(null);
            String answer = best != null ? synthesizeBestPath(tree, goal) : labels.getNoSolution();
            return new SimplePlan(List.of(SimplePlanStep.done(answer)));
        }

        ThoughtNode current = frontier.get(0);

        List<String> newThoughts = generateThoughts(current.getContent(), goal);

        double bestScore = -1;
        ThoughtNode bestNode = null;

        for (String thought : newThoughts) {
            ThoughtNode child = tree.addNode(thought, current.getId());
            EvalResult eval = evaluateThought(thought, goal);
            child.setScore(eval.score);
            child.setTerminal(eval.done);

            if (eval.done && eval.finalAnswer != null) {
                saveTree(session, tree);
                log.debug("[ToT] Terminal thought found for session {}", session.getId());
                return new SimplePlan(List.of(SimplePlanStep.done(eval.finalAnswer)));
            }

            if (eval.score > bestScore) {
                bestScore = eval.score;
                bestNode = child;
            }
        }

        saveTree(session, tree);

        if (bestNode != null && bestNode.getDepth() >= maxDepth) {
            return new SimplePlan(List.of(SimplePlanStep.done(synthesizeBestPath(tree, goal))));
        }

        String reasoning = bestNode != null ? bestNode.getContent() : labels.getContinueExpansion();
        log.debug("[ToT] Best thought score={} depth={} for session {}", bestScore,
                bestNode != null ? bestNode.getDepth() : 0, session.getId());

        return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null, reasoning, false)));
    }

    private List<ThoughtNode> selectFrontier(ThoughtGraph tree) {
        List<ThoughtNode> leaves = tree.getNodes().values().stream()
                .filter(n -> n.getChildIds().isEmpty() && !n.isTerminal() && n.getDepth() < maxDepth)
                .collect(Collectors.toList());

        if (leaves.isEmpty()) return List.of();

        return switch (searchAlgorithm) {
            case "dfs" -> leaves.stream()
                    .sorted(Comparator.comparingInt(ThoughtNode::getDepth).reversed())
                    .limit(1).collect(Collectors.toList());
            case "beam" -> leaves.stream()
                    .sorted(Comparator.comparingDouble(ThoughtNode::getScore).reversed())
                    .limit(beamWidth).collect(Collectors.toList());
            default -> // bfs
                    leaves.stream()
                            .sorted(Comparator.comparingInt(ThoughtNode::getDepth))
                            .limit(1).collect(Collectors.toList());
        };
    }

    private List<String> generateThoughts(String currentThought, String goal) {
        try {
            String system = promptLoader.load(generatePromptPath,
                    Map.of("count", thoughtsPerStep));
            String userMsg = labels.getTask() + goal + labels.getCurrentReasoning() + currentThought;

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(userMsg)
            ))).call().content();

            return parseStringArray(response);
        } catch (Exception e) {
            log.warn("[ToT] Thought generation failed: {}", e.getMessage());
            return List.of(labels.getContinueExpansion() + " " + currentThought);
        }
    }

    private EvalResult evaluateThought(String thought, String goal) {
        try {
            String system = promptLoader.load(evaluatePromptPath, Map.of());
            String userMsg = labels.getTask() + goal + labels.getThoughtToEvaluate() + thought;

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(userMsg)
            ))).call().content();

            return parseEvalResult(response);
        } catch (Exception e) {
            EvalResult r = new EvalResult();
            r.score = 0.5;
            return r;
        }
    }

    @SuppressWarnings("unchecked")
    private EvalResult parseEvalResult(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
            EvalResult r = new EvalResult();
            r.score = ((Number) map.getOrDefault("score", 0.5)).doubleValue();
            r.done = Boolean.TRUE.equals(map.get("done"));
            r.finalAnswer = (String) map.get("final_answer");
            return r;
        } catch (Exception e) {
            EvalResult r = new EvalResult();
            r.score = 0.5;
            return r;
        }
    }

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

    private ThoughtGraph loadOrCreateTree(SessionState session, String goal) {
        Object raw = session.getAttributes().get(TREE_KEY);
        if (raw != null) {
            try {
                return objectMapper.convertValue(raw, ThoughtGraph.class);
            } catch (Exception ignored) {
            }
        }
        ThoughtGraph tree = ThoughtGraph.withRoot(goal);
        saveTree(session, tree);
        return tree;
    }

    private void saveTree(SessionState session, ThoughtGraph tree) {
        session.getAttributes().put(TREE_KEY, tree);
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

    private String synthesizeBestPath(ThoughtGraph tree, String goal) {
        ThoughtNode best = tree.bestNode().orElse(null);
        if (best == null) return labels.getNoSolution();

        List<ThoughtNode> path = new ArrayList<>();
        ThoughtNode node = best;
        while (node != null && !node.getId().equals(tree.getRootId())) {
            path.add(0, node);
            String parentId = node.getParentId();
            node = parentId != null ? tree.getNodes().get(parentId) : null;
        }

        if (path.isEmpty()) return best.getContent();

        String thoughts = IntStream.range(0, path.size())
                .mapToObj(i -> (i + 1) + ". " + path.get(i).getContent())
                .collect(Collectors.joining("\n\n"));

        try {
            String system = promptLoader.load(synthesizePromptPath, Map.of());
            String userMsg = labels.getTask() + goal + "\n\n" + labels.getBestReasoningPath() + thoughts;
            String result = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(userMsg)
            ))).call().content();
            return result != null ? result.strip() : best.getContent();
        } catch (Exception e) {
            log.warn("[ToT] Synthesis failed: {}", e.getMessage());
            return best.getContent();
        }
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

    private static class EvalResult {
        double score;
        boolean done;
        String finalAnswer;
    }
}
