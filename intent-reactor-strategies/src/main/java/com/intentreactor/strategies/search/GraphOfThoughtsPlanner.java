package com.intentreactor.strategies.search;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Graph-of-Thoughts (GoT) planner: maintains a directed acyclic graph of thoughts.
 * At each step, the LLM selects an operation (GENERATE, AGGREGATE, REFINE, SCORE)
 * to evolve the graph. More flexible than ToT as it supports merging and refining thoughts.
 * <p>
 * Prompt file configured via intent-reactor.planning.strategies.prompts.got-generate
 * <p>
 * Activate with: intent-reactor.planning.strategy: got
 */
public class GraphOfThoughtsPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(GraphOfThoughtsPlanner.class);
    private static final String GRAPH_KEY = StrategySessionKeys.GOT_GRAPH;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int maxOperations;
    private final double aggregationThreshold;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String generatePromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public GraphOfThoughtsPlanner(ChatClient chatClient, ObjectMapper objectMapper,
                                  StrategiesProperties props) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.maxOperations = props.getGot().getMaxOperations();
        this.aggregationThreshold = props.getGot().getAggregationThreshold();
        this.generatePromptPath = props.getPrompts().getGotGenerate();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String goal = getGoal(session);
        ThoughtGraph graph = loadOrCreateGraph(session, goal);

        if (graph.getOperationCount() >= maxOperations) {
            Optional<ThoughtNode> best = graph.bestNode();
            String answer = best.map(ThoughtNode::getContent).orElse(labels.getNoSolution());
            return new SimplePlan(List.of(SimplePlanStep.done(answer)));
        }

        String graphSummary = buildGraphSummary(graph);

        try {
            String system = promptLoader.load(generatePromptPath, Map.of());
            String userMsg = labels.getTask() + goal + labels.getCurrentGraph() + graphSummary
                    + labels.getChooseNextOperation();

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(userMsg)
            ))).call().content();

            OperationResult op = parseOperation(response);
            graph.setOperationCount(graph.getOperationCount() + 1);

            if (op.done && op.finalAnswer != null) {
                saveGraph(session, graph);
                return new SimplePlan(List.of(SimplePlanStep.done(op.finalAnswer)));
            }

            applyOperation(graph, op);
            saveGraph(session, graph);

            Optional<ThoughtNode> best = graph.bestNode();
            if (best.isPresent() && best.get().getScore() >= aggregationThreshold) {
                return new SimplePlan(List.of(SimplePlanStep.done(best.get().getContent())));
            }

            String reasoning = op.content != null ? op.content : labels.getContinueExpansion();
            log.debug("[GoT] Operation {} applied, total ops={} for session {}", op.operation,
                    graph.getOperationCount(), session.getId());

            return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null, reasoning, false)));

        } catch (Exception e) {
            log.warn("[GoT] Operation failed: {}", e.getMessage());
            return new SimplePlan(List.of(SimplePlanStep.fail("Graph operation failed: " + e.getMessage())));
        }
    }

    private void applyOperation(ThoughtGraph graph, OperationResult op) {
        switch (op.operation) {
            case "GENERATE" -> {
                String parentId = (op.sourceIds != null && !op.sourceIds.isEmpty())
                        ? op.sourceIds.get(0) : graph.getRootId();
                graph.addNode(op.content, parentId);
            }
            case "AGGREGATE" -> {
                if (op.sourceIds != null && op.sourceIds.size() >= 2) {
                    graph.aggregate(op.sourceIds, op.content);
                }
            }
            case "REFINE" -> {
                if (op.sourceIds != null && !op.sourceIds.isEmpty()) {
                    ThoughtNode node = graph.getNodes().get(op.sourceIds.get(0));
                    if (node != null) node.setContent(op.content);
                }
            }
            case "SCORE" -> {
                if (op.sourceIds != null && !op.sourceIds.isEmpty() && op.score != null) {
                    ThoughtNode node = graph.getNodes().get(op.sourceIds.get(0));
                    if (node != null) node.setScore(op.score);
                }
            }
        }
    }

    private String buildGraphSummary(ThoughtGraph graph) {
        return graph.getNodes().values().stream()
                .map(n -> String.format("ID=%s depth=%d score=%.2f: %s",
                        n.getId().substring(0, 8), n.getDepth(), n.getScore(),
                        n.getContent() != null ? n.getContent().substring(0, Math.min(100, n.getContent().length())) : ""))
                .collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    private OperationResult parseOperation(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
            OperationResult r = new OperationResult();
            r.operation = (String) map.getOrDefault("operation", "GENERATE");
            r.sourceIds = (List<String>) map.get("source_ids");
            r.content = (String) map.get("content");
            r.score = map.get("score") instanceof Number ? ((Number) map.get("score")).doubleValue() : null;
            r.done = Boolean.TRUE.equals(map.get("done"));
            r.finalAnswer = (String) map.get("final_answer");
            return r;
        } catch (Exception e) {
            OperationResult r = new OperationResult();
            r.operation = "GENERATE";
            r.content = labels.getContinueExpansion();
            return r;
        }
    }

    private ThoughtGraph loadOrCreateGraph(SessionState session, String goal) {
        Object raw = session.getAttributes().get(GRAPH_KEY);
        if (raw != null) {
            try {
                return objectMapper.convertValue(raw, ThoughtGraph.class);
            } catch (Exception ignored) {
            }
        }
        ThoughtGraph graph = ThoughtGraph.withRoot(goal);
        saveGraph(session, graph);
        return graph;
    }

    private void saveGraph(SessionState session, ThoughtGraph graph) {
        session.getAttributes().put(GRAPH_KEY, graph);
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

    private static class OperationResult {
        String operation;
        List<String> sourceIds;
        String content;
        Double score;
        boolean done;
        String finalAnswer;
    }
}
