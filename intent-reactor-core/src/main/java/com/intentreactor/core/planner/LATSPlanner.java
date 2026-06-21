package com.intentreactor.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Planner;
import com.intentreactor.api.PromptContextProvider;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.SimulatableTool;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.planner.search.DefaultNodeEvaluator;
import com.intentreactor.core.planner.search.DefaultSearchTree;
import com.intentreactor.core.planner.search.NodeEvaluator;
import com.intentreactor.core.planner.search.SearchNode;
import com.intentreactor.core.planner.search.SearchTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LATSPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(LATSPlanner.class);
    private static final String SEARCH_TREE_KEY = "searchTree";
    private static final String STEP_INDEX_KEY = "lats_step_index";
    // Committed action path: List<Map<{toolName, parameters}>> stored after the first MCTS run.
    // MCTS runs only once per goal; subsequent plan() calls advance stepIndex through this list
    // without re-running MCTS (which would change bestPath and reset stepIndex unpredictably).
    private static final String COMMITTED_ACTIONS_KEY = "lats_committed_actions";
    // Goal string stored as a plain session attribute for goal-change detection that is independent
    // of SearchTree deserialization (tree may deserialize as Map when loaded from filesystem store).
    private static final String LATS_GOAL_KEY = "lats_goal";

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final IntentReactorProperties properties;
    private final ObjectMapper objectMapper;
    private final NodeEvaluator nodeEvaluator;
    private final List<PromptContextProvider> promptContextProviders;

    public LATSPlanner(ChatClient chatClient,
                       ToolProvider toolProvider,
                       IntentReactorProperties properties,
                       ObjectMapper objectMapper) {
        this(chatClient, toolProvider, properties, objectMapper, List.of());
    }

    public LATSPlanner(ChatClient chatClient,
                       ToolProvider toolProvider,
                       IntentReactorProperties properties,
                       ObjectMapper objectMapper,
                       List<PromptContextProvider> promptContextProviders) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.promptContextProviders = promptContextProviders != null ? promptContextProviders : List.of();
        IntentReactorProperties.LatsConfig cfg = properties.getPlanning().getLats();
        this.nodeEvaluator = new DefaultNodeEvaluator(chatClient, objectMapper, cfg.getBranchingFactor(), properties);
    }

    // MessageCompressor is accepted for API consistency but not applied — LATS manages
    // its own history as a serialized search tree and doesn't use buildMessages().
    public LATSPlanner(ChatClient chatClient,
                       ToolProvider toolProvider,
                       IntentReactorProperties properties,
                       ObjectMapper objectMapper,
                       List<PromptContextProvider> promptContextProviders,
                       MessageCompressor ignoredCompressor) {
        this(chatClient, toolProvider, properties, objectMapper, promptContextProviders);
    }

    @Override
    public Plan plan(SessionState sessionState, IntentAnalysisResult intent) {
        IntentReactorProperties.LatsConfig cfg = properties.getPlanning().getLats();
        String goal = deriveGoal(intent);
        List<Tool> tools = toolProvider.getAvailableTools(sessionState);

        // Reset committed path when goal changes. Use a plain string attribute — independent of
        // SearchTree deserialization (tree may load as a raw Map from filesystem session store,
        // causing instanceof SearchTree to fail and missing the goal-change reset).
        String storedGoal = (String) sessionState.getAttributes().getOrDefault(LATS_GOAL_KEY, "");
        if (!storedGoal.equals(goal)) {
            sessionState.getAttributes().remove(COMMITTED_ACTIONS_KEY);
            sessionState.getAttributes().remove(STEP_INDEX_KEY);
            sessionState.getAttributes().put(LATS_GOAL_KEY, goal);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> committedActions =
                (List<Map<String, Object>>) sessionState.getAttributes().get(COMMITTED_ACTIONS_KEY);

        if (committedActions == null) {
            // First call for this goal: run MCTS once and commit the resulting path.
            SearchTree tree = getOrCreateTree(sessionState, goal, cfg.getBranchingFactor());
            sessionState.getAttributes().put(LATS_GOAL_KEY, goal);
            tree.getRoot().getState().put("history", buildHistory(sessionState));
            tree.getRoot().getState().put("tools", buildToolsDescription(tools));
            for (PromptContextProvider provider : promptContextProviders) {
                tree.getRoot().getState().putAll(provider.getAdditionalVariables(sessionState));
            }

            for (int i = 0; i < cfg.getMaxIterations(); i++) {
                SearchNode leaf = tree.selectLeaf(cfg.getExplorationConstant());
                if (leaf.getChildren().isEmpty()) {
                    List<Action> actions = nodeEvaluator.generateActions(leaf);
                    if (actions.isEmpty()) {
                        tree.backpropagate(leaf, 0.0);
                        continue;
                    }
                    int limit = cfg.getBranchingFactor();
                    List<Action> bounded = actions.size() > limit ? actions.subList(0, limit) : actions;
                    tree.expand(leaf, bounded);
                }
                for (SearchNode child : leaf.getChildren()) {
                    double reward = simulate(child, tools, cfg);
                    tree.backpropagate(child, reward);
                }
            }

            List<SearchNode> bestPath = tree.bestPath();
            saveTree(sessionState, tree);

            if (bestPath.isEmpty()) {
                return new SimplePlan(List.of(SimplePlanStep.fail("LATS could not find a valid plan")));
            }

            committedActions = bestPath.stream()
                    .filter(n -> n.getAction() != null)
                    .map(n -> {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("toolName", n.getAction().toolName());
                        entry.put("parameters", n.getAction().parameters());
                        return entry;
                    })
                    .collect(Collectors.toList());

            log.debug("LATS committed path for goal '{}': {}", goal,
                    committedActions.stream().map(e -> (String) e.get("toolName")).collect(Collectors.joining(" → ")));

            sessionState.getAttributes().put(COMMITTED_ACTIONS_KEY, committedActions);
            sessionState.getAttributes().put(STEP_INDEX_KEY, 0);
        }

        int stepIndex = ((Number) sessionState.getAttributes()
                .getOrDefault(STEP_INDEX_KEY, 0)).intValue();
        sessionState.getAttributes().put(STEP_INDEX_KEY, stepIndex + 1);

        if (stepIndex >= committedActions.size()) {
            // All committed actions executed — clear state so the next user request starts fresh.
            sessionState.getAttributes().remove(COMMITTED_ACTIONS_KEY);
            sessionState.getAttributes().remove(STEP_INDEX_KEY);
            sessionState.getAttributes().remove(LATS_GOAL_KEY);
            return buildSynthesisPlan(goal, sessionState);
        }

        return buildPlanFromCommittedActions(committedActions, stepIndex, tools);
    }

    private SearchTree getOrCreateTree(SessionState session, String goal, int branchingFactor) {
        Object existing = session.getAttributes().get(SEARCH_TREE_KEY);
        if (existing instanceof SearchTree existingTree) {
            String existingGoal = (String) existingTree.getRoot().getState().getOrDefault("goal", "");
            if (existingGoal.equals(goal)) {
                return existingTree;
            }
        }
        return new DefaultSearchTree(goal, branchingFactor);
    }

    private void saveTree(SessionState session, SearchTree tree) {
        session.getAttributes().put(SEARCH_TREE_KEY, tree);
    }

    private Plan buildSynthesisPlan(String goal, SessionState sessionState) {
        List<String> toolResults = sessionState.getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.SYSTEM)
                .map(Message::getContent)
                .collect(Collectors.toList());

        if (toolResults.isEmpty()) {
            return new SimplePlan(List.of(SimplePlanStep.done("LATS plan completed")));
        }

        String userPrompt = "Goal: " + goal + "\n\nTool execution results:\n"
                + String.join("\n---\n", toolResults)
                + "\n\nProvide a clear, concise, human-readable summary of the accomplished plan for the user.";
        try {
            String response = chatClient.prompt(new Prompt(List.of(new UserMessage(userPrompt))))
                    .call().content();
            String answer = (response != null && !response.isBlank()) ? response.trim() : "LATS plan completed";
            return new SimplePlan(List.of(SimplePlanStep.done(answer)));
        } catch (Exception e) {
            log.warn("LATS synthesis failed: {}", e.getMessage());
            return new SimplePlan(List.of(SimplePlanStep.done("LATS plan completed")));
        }
    }

    @SuppressWarnings("unchecked")
    private Plan buildPlanFromCommittedActions(List<Map<String, Object>> committedActions,
                                               int fromIndex, List<Tool> tools) {
        List<PlanStep> steps = new ArrayList<>();
        for (int i = fromIndex; i < committedActions.size(); i++) {
            Map<String, Object> entry = committedActions.get(i);
            String toolName = (String) entry.get("toolName");
            Map<String, Object> params = entry.containsKey("parameters")
                    ? (Map<String, Object>) entry.get("parameters") : Map.of();
            Action action = new SimpleAction(toolName, params);
            Tool tool = findTool(tools, toolName);
            boolean needsConfirmation = tool != null && tool.isRisky()
                    && !properties.getPlanning().isAutonomous();
            steps.add(SimplePlanStep.act(action, "Execute " + toolName, needsConfirmation));
        }
        if (steps.isEmpty()) {
            steps.add(SimplePlanStep.fail("No valid actions in committed path"));
        } else {
            steps.add(SimplePlanStep.done("LATS plan completed"));
        }
        return new SimplePlan(steps);
    }

    private double simulate(SearchNode node, List<Tool> tools, IntentReactorProperties.LatsConfig cfg) {
        int depth = cfg.getSimulationDepth();
        double totalReward = 0.0;
        int stepsSimulated = 0;
        SearchNode current = node;

        for (int d = 0; d < depth; d++) {
            Action action = current.getAction();
            if (action == null) {
                totalReward += 0.5;
                stepsSimulated++;
                break;
            }

            Tool tool = findTool(tools, action.toolName());
            if (tool == null) {
                log.debug("No tool found for action '{}', using low reward", action.toolName());
                totalReward += 0.1;
                stepsSimulated++;
                break;
            }

            ToolResult result;
            if (tool instanceof SimulatableTool simulatable) {
                try {
                    result = simulatable.simulate(new ToolInput(action.parameters(), null));
                } catch (UnsupportedOperationException e) {
                    log.debug("Tool '{}' does not support simulation, using neutral reward", action.toolName());
                    totalReward += 0.5;
                    stepsSimulated++;
                    break;
                }
            } else if (cfg.isAllowRealActionsInSimulation()) {
                log.debug("Executing real action '{}' in simulation mode", action.toolName());
                result = tool.execute(new ToolInput(action.parameters(), null));
            } else {
                log.debug("No SimulatableTool for '{}', using neutral reward 0.5", action.toolName());
                totalReward += 0.5;
                stepsSimulated++;
                break;
            }

            double reward = nodeEvaluator.evaluate(current, result);
            totalReward += reward;
            stepsSimulated++;

            if (d < depth - 1 && !current.getChildren().isEmpty()) {
                SearchNode next = current.getChildren().stream()
                        .max(Comparator.comparingDouble(SearchNode::averageValue)).orElse(null);
                if (next == null) break;
                current = next;
            }
        }

        return stepsSimulated > 0 ? totalReward / stepsSimulated : 0.5;
    }

    private String buildToolsDescription(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            try {
                sb.append("  Parameters: ").append(objectMapper.writeValueAsString(tool.getParameterSchema())).append("\n");
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    private String buildHistory(SessionState session) {
        int maxMsgs = properties.getPlanning().getContextWindow().getMaxMessages();
        List<Message> all = session.getMessages();
        List<Message> window = (maxMsgs > 0 && all.size() > maxMsgs)
                ? all.subList(all.size() - maxMsgs, all.size()) : all;
        if (window.isEmpty()) return "(история пуста)";
        StringBuilder sb = new StringBuilder();
        for (Message m : window) {
            sb.append("[").append(m.getRole()).append("] ").append(m.getContent()).append("\n");
        }
        return sb.toString();
    }

    private Tool findTool(List<Tool> tools, String name) {
        return tools.stream().filter(t -> t.getName().equals(name)).findFirst().orElse(null);
    }

    private String deriveGoal(IntentAnalysisResult intent) {
        if (intent.getReasoningSuggestion() != null && !intent.getReasoningSuggestion().isBlank()) {
            return intent.getReasoningSuggestion();
        }
        if (intent.hasIntents()) {
            return "Fulfill intent: " + intent.primaryIntent().getName();
        }
        return "Process user request";
    }
}
