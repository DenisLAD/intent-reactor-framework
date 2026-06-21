package com.intentreactor.core.planner.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
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

public class DefaultNodeEvaluator implements NodeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(DefaultNodeEvaluator.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int branchingFactor;
    private final IntentReactorProperties properties;
    private final PromptLoader promptLoader = new PromptLoader();

    public DefaultNodeEvaluator(ChatClient chatClient, ObjectMapper objectMapper,
                                int branchingFactor, IntentReactorProperties properties) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.branchingFactor = branchingFactor;
        this.properties = properties;
    }

    @Override
    public List<Action> generateActions(SearchNode node) {
        String goal = (String) node.getState().getOrDefault("goal", "Accomplish the user's goal");
        String history = (String) node.getState().getOrDefault("history", "(история пуста)");
        String tools = (String) node.getState().getOrDefault("tools", "");
        IntentReactorProperties.PromptResources pr = properties.getLlm().getPromptResources();

        // Start with all extra String vars stored by PromptContextProviders in the node state.
        Map<String, Object> extraVars = buildExtraVars(node);
        Map<String, Object> systemVars = new HashMap<>(extraVars);
        systemVars.put("goal", goal);
        systemVars.put("tools", tools);
        Map<String, Object> userVars = new HashMap<>(extraVars);
        userVars.put("goal", goal);
        userVars.put("branchingFactor", String.valueOf(branchingFactor));
        userVars.put("history", history);
        userVars.put("tools", tools);

        String systemPrompt = promptLoader.load(pr.getLatsActionsSystem(), systemVars);
        String userPrompt = promptLoader.load(pr.getLatsActionsUser(), userVars);

        try {
            String response = chatClient.prompt(
                    new Prompt(List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)))
            ).call().content();

            return parseActions(response);
        } catch (Exception e) {
            log.warn("Failed to generate actions for node {}: {}", node.getId(), e.getMessage());
            return List.of();
        }
    }

    @Override
    public double evaluate(SearchNode node, ToolResult simulationResult) {
        if (simulationResult == null) return 0.5;
        if (!simulationResult.isSuccess()) return 0.1;

        String goal = (String) node.getState().getOrDefault("goal", "Accomplish the user's goal");
        String history = (String) node.getState().getOrDefault("history", "(история пуста)");
        IntentReactorProperties.PromptResources pr = properties.getLlm().getPromptResources();
        Map<String, Object> userVars = new HashMap<>(buildExtraVars(node));
        userVars.put("goal", goal);
        userVars.put("actionResult", String.valueOf(simulationResult.getData()));
        userVars.put("history", history);
        String systemPrompt = promptLoader.load(pr.getLatsEvaluateSystem());
        String userPrompt = promptLoader.load(pr.getLatsEvaluateUser(), userVars);

        try {
            String response = chatClient.prompt(
                    new Prompt(List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)))
            ).call().content();

            return parseScore(response.trim());
        } catch (Exception e) {
            log.warn("Failed to evaluate node {}: {}", node.getId(), e.getMessage());
            return 0.5;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Action> parseActions(String response) {
        int start = response.indexOf('[');
        if (start < 0) return List.of();

        // Try full well-formed array first
        int end = response.lastIndexOf(']');
        if (end > start) {
            try {
                List<Map<String, Object>> parsed = objectMapper.readValue(
                        response.substring(start, end + 1), List.class);
                return buildActions(parsed);
            } catch (Exception ignored) {
                // fall through to per-object extraction
            }
        }

        // Truncated response: extract every complete JSON object individually.
        // This recovers the first N-1 actions when the N-th action is cut off.
        List<Action> result = new ArrayList<>();
        String fragment = response.substring(start);
        int i = 0;
        while (i < fragment.length()) {
            int objStart = fragment.indexOf('{', i);
            if (objStart < 0) break;

            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            int objEnd = -1;
            for (int j = objStart; j < fragment.length(); j++) {
                char c = fragment.charAt(j);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\' && inString) {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) continue;
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        objEnd = j;
                        break;
                    }
                }
            }
            if (objEnd < 0) break; // truncated object — nothing more to extract

            try {
                Map<String, Object> item = objectMapper.readValue(
                        fragment.substring(objStart, objEnd + 1), Map.class);
                String toolName = (String) item.get("toolName");
                Map<String, Object> params = item.containsKey("parameters")
                        ? (Map<String, Object>) item.get("parameters") : Map.of();
                if (toolName != null) result.add(new SimpleAction(toolName, params));
            } catch (Exception ignored) {
            }
            i = objEnd + 1;
        }
        return result;
    }

    private List<Action> buildActions(List<Map<String, Object>> parsed) {
        List<Action> actions = new ArrayList<>();
        for (Map<String, Object> item : parsed) {
            String toolName = (String) item.get("toolName");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = item.containsKey("parameters")
                    ? (Map<String, Object>) item.get("parameters") : Map.of();
            if (toolName != null) actions.add(new SimpleAction(toolName, params));
        }
        return actions;
    }

    private Map<String, Object> buildExtraVars(SearchNode node) {
        Map<String, Object> extra = new HashMap<>();
        node.getState().forEach((k, v) -> {
            if (v instanceof String) extra.put(k, v);
        });
        return extra;
    }

    private double parseScore(String response) {
        try {
            double value = Double.parseDouble(response);
            return Math.max(0.0, Math.min(1.0, value));
        } catch (NumberFormatException e) {
            // try to find a number in the response
            for (String token : response.split("\\s+")) {
                try {
                    double value = Double.parseDouble(token);
                    return Math.max(0.0, Math.min(1.0, value));
                } catch (NumberFormatException ignored) {
                }
            }
            return 0.5;
        }
    }
}
