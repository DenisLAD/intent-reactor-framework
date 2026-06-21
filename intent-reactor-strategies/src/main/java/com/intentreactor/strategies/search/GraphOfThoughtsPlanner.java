package com.intentreactor.strategies.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Graph-of-Thoughts (GoT) planner: maintains a directed acyclic graph of thoughts.
 * At each step, the LLM selects an operation (GENERATE, AGGREGATE, REFINE, SCORE)
 * to evolve the graph. More flexible than ToT as it supports merging and refining thoughts.
 * <p>
 * Operations:
 * GENERATE  - add a new thought as a child of an existing node
 * AGGREGATE - merge multiple nodes into a new synthesis node
 * REFINE    - update the content of an existing node
 * SCORE     - assign a quality score to a node
 * <p>
 * Stored in session.attributes:
 * got_graph : ThoughtGraph
 * <p>
 * Activate with: intent-reactor.planning.strategy: got
 */
public class GraphOfThoughtsPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(GraphOfThoughtsPlanner.class);
    private static final String GRAPH_KEY = "got_graph";

    private static final String OPERATION_SYSTEM =
            "Ты управляешь графом мыслей для решения задачи. " +
                    "Выбери следующую операцию для развития графа:\n\n" +
                    "- GENERATE: создай новую мысль как дочернюю к существующей\n" +
                    "- AGGREGATE: объедини несколько мыслей в одну синтезирующую\n" +
                    "- REFINE: уточни или улучши существующую мысль\n" +
                    "- SCORE: оцени качество мысли (от 0.0 до 1.0)\n\n" +
                    "Верни JSON:\n" +
                    "{\n" +
                    "  \"operation\": \"GENERATE\",\n" +
                    "  \"source_ids\": [\"id1\"],\n" +
                    "  \"content\": \"Новая мысль или уточнение\",\n" +
                    "  \"score\": null,\n" +
                    "  \"done\": false,\n" +
                    "  \"final_answer\": null\n" +
                    "}\n\n" +
                    "done: true если найден финальный ответ. final_answer: текст ответа если done=true.";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int maxOperations;
    private final double aggregationThreshold;

    public GraphOfThoughtsPlanner(ChatClient chatClient, ObjectMapper objectMapper,
                                  StrategiesProperties props) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.maxOperations = props.getGot().getMaxOperations();
        this.aggregationThreshold = props.getGot().getAggregationThreshold();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String goal = getGoal(intent);
        ThoughtGraph graph = loadOrCreateGraph(session, goal);

        if (graph.getOperationCount() >= maxOperations) {
            Optional<ThoughtNode> best = graph.bestNode();
            String answer = best.map(ThoughtNode::getContent).orElse("No solution found.");
            return new SimplePlan(List.of(SimplePlanStep.done(answer)));
        }

        // Build graph summary for LLM
        String graphSummary = buildGraphSummary(graph);

        try {
            String userMsg = "Задача: " + goal + "\n\nТекущий граф мыслей:\n" + graphSummary +
                    "\n\nВыбери следующую операцию.";

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(OPERATION_SYSTEM),
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

            // Check if best node exceeds aggregation threshold
            Optional<ThoughtNode> best = graph.bestNode();
            if (best.isPresent() && best.get().getScore() >= aggregationThreshold) {
                return new SimplePlan(List.of(SimplePlanStep.done(best.get().getContent())));
            }

            String reasoning = op.content != null ? op.content : "Exploring graph...";
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
            r.content = "Exploring...";
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

    private String getGoal(IntentAnalysisResult intent) {
        if (intent != null && intent.getReasoningSuggestion() != null
                && !intent.getReasoningSuggestion().isBlank()) {
            return intent.getReasoningSuggestion();
        }
        if (intent != null && !intent.getIntents().isEmpty()) {
            return intent.getIntents().get(0).getName();
        }
        return "Process user request";
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
