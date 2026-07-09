package com.intentreactor.strategies.deliberation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Plan;
import com.intentreactor.api.PlanStep;
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
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.config.StrategySessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SAND (Self-taught ActioN Deliberation) decorator planner.
 * <p>
 * Intercepts ACT steps from the delegate: generates N-1 alternative actions via LLM,
 * predicts outcomes for each candidate (via SimulatableTool.simulate() or LLM), scores
 * each (action, prediction) pair, and selects the highest-scoring candidate.
 * All deliberation data is stored in session.attributes for optional training-data collection.
 * <p>
 * Activate with: intent-reactor.planning.strategy: sand
 */
public class SANDPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(SANDPlanner.class);

    private final Planner delegate;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ToolProvider toolProvider;
    private final int numCandidates;
    private final boolean useSimulation;
    private final String evaluationMethod;
    private final int maxTrainingLogEntries;
    private final boolean autonomous;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String candidatesPromptPath;
    private final String predictPromptPath;
    private final String evaluatePromptPath;

    public SANDPlanner(Planner delegate, ChatClient chatClient, ObjectMapper objectMapper,
                       ToolProvider toolProvider, StrategiesProperties props,
                       IntentReactorProperties intentReactorProperties) {
        this.delegate = delegate;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.toolProvider = toolProvider;
        StrategiesProperties.SandConfig cfg = props.getSand();
        this.numCandidates = cfg.getNumCandidates();
        this.useSimulation = cfg.isUseSimulation();
        this.evaluationMethod = cfg.getEvaluationMethod();
        this.maxTrainingLogEntries = cfg.getMaxTrainingLogEntries();
        this.autonomous = intentReactorProperties.getPlanning().isAutonomous();
        StrategiesProperties.PromptsConfig prompts = props.getPrompts();
        this.candidatesPromptPath = prompts.getSandCandidates();
        this.predictPromptPath = prompts.getSandPredict();
        this.evaluatePromptPath = prompts.getSandEvaluate();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        Plan delegatePlan = delegate.plan(session, intent);

        List<PlanStep> steps = delegatePlan.steps();
        Optional<PlanStep> actStepOpt = steps.stream()
                .filter(s -> s.type() == StepType.ACT)
                .findFirst();
        if (actStepOpt.isEmpty()) return delegatePlan;
        PlanStep actStep = actStepOpt.get();

        List<Tool> tools = toolProvider.getAvailableTools(session);
        Set<String> toolNames = tools.stream().map(Tool::getName).collect(Collectors.toSet());

        String goal = extractGoal(session, intent);
        String context = buildContextSummary(session);
        String toolsText = formatTools(tools);

        var delegateAction = actStep.action();
        String delegateToolName = delegateAction != null ? delegateAction.toolName() : "";
        Map<String, Object> delegateParams = delegateAction != null ? delegateAction.parameters() : Map.of();

        // Candidate 0 = delegate proposal (free)
        SandCandidate candidate0 = new SandCandidate(0, delegateToolName, delegateParams,
                actStep.description() != null ? actStep.description() : "Delegate proposal");
        List<SandCandidate> candidates = new ArrayList<>();
        candidates.add(candidate0);

        // Generate alternatives
        if (numCandidates > 1) {
            List<SandCandidate> alternatives = generateAlternatives(
                    session, goal, context, toolsText, delegateToolName, delegateParams,
                    numCandidates - 1, toolNames);
            candidates.addAll(alternatives);
        }

        // Predict outcomes
        predictOutcomes(candidates, tools, context, goal, session);

        // Score
        scoreAll(candidates, tools, goal, context);

        // Find best
        SandCandidate best = candidates.stream()
                .max((a, b) -> Double.compare(a.getScore(), b.getScore()))
                .orElse(candidate0);

        int stepCount = (int) session.getAttributes().getOrDefault(StrategySessionKeys.SAND_STEP_COUNT, 0);
        storeTrainingEntry(session, stepCount, goal, context, candidates, best.getIndex());
        session.getAttributes().put(StrategySessionKeys.SAND_STEP_COUNT, stepCount + 1);

        log.debug("[SAND] step={} candidates={} best=#{} tool={} score={}",
                stepCount, candidates.size(), best.getIndex(), best.getToolName(), best.getScore());

        if (best.getIndex() == 0) {
            return delegatePlan;
        }

        boolean isRisky = tools.stream()
                .anyMatch(t -> t.getName().equals(best.getToolName()) && t.isRisky());
        boolean needsConfirmation = isRisky && !autonomous;
        String desc = "[SAND] " + best.getReasoning();
        PlanStep newActStep = SimplePlanStep.act(
                new SimpleAction(best.getToolName(), best.getParameters()), desc, needsConfirmation);
        List<PlanStep> newSteps = steps.stream()
                .map(s -> s == actStep ? newActStep : s)
                .toList();
        return new SimplePlan(newSteps);
    }

    private List<SandCandidate> generateAlternatives(SessionState session, String goal, String context,
                                                     String toolsText, String excludedTool,
                                                     Map<String, Object> excludedParams, int count,
                                                     Set<String> validToolNames) {
        List<SandCandidate> result = new ArrayList<>();
        try {
            String excludedParamsStr = writeJson(excludedParams);
            String prompt = promptLoader.load(candidatesPromptPath, Map.of(
                    "goal", goal,
                    "context", context,
                    "tools", toolsText,
                    "count", String.valueOf(count),
                    "excludedTool", excludedTool,
                    "excludedParams", excludedParamsStr
            ));
            String response = chatClient.prompt(new Prompt(List.of(new UserMessage(prompt)))).call().content();
            List<Map<String, Object>> parsed = parseJsonArray(response);
            int idx = 1;
            for (Map<String, Object> item : parsed) {
                String toolName = String.valueOf(item.getOrDefault("toolName", ""));
                if (!validToolNames.contains(toolName)) {
                    log.debug("[SAND] Filtered hallucinated toolName: {}", toolName);
                    continue;
                }
                Object rawParams = item.get("parameters");
                Map<String, Object> params = rawParams instanceof Map<?, ?> rawMap
                        ? castMap(rawMap) : Map.of();
                String reasoning = String.valueOf(item.getOrDefault("reasoning", "Alternative candidate"));
                result.add(new SandCandidate(idx++, toolName, params, reasoning));
            }
        } catch (Exception e) {
            log.warn("[SAND] generateAlternatives failed: {}", e.getMessage());
        }
        return result;
    }

    private void predictOutcomes(List<SandCandidate> candidates, List<Tool> tools,
                                 String context, String goal, SessionState session) {
        Map<String, Tool> toolMap = tools.stream().collect(Collectors.toMap(Tool::getName, t -> t));
        for (SandCandidate candidate : candidates) {
            Tool tool = toolMap.get(candidate.getToolName());
            boolean canSimulate = useSimulation && tool instanceof SimulatableTool;
            if (canSimulate && !"llm".equalsIgnoreCase(evaluationMethod)) {
                try {
                    var simResult = ((SimulatableTool) tool).simulate(
                            new ToolInput(candidate.getParameters(), session.getId()));
                    candidate.setPrediction(simResult.isSuccess()
                            ? simResult.getData().toString() : "ERROR: " + simResult.getData());
                    candidate.setFromSimulation(true);
                    if ("simulation".equalsIgnoreCase(evaluationMethod)) {
                        candidate.setScore(simResult.isSuccess() ? 1.0 : 0.5);
                    }
                    continue;
                } catch (Exception e) {
                    log.debug("[SAND] simulate() failed for {}: {}", candidate.getToolName(), e.getMessage());
                }
            }
            if (!"simulation".equalsIgnoreCase(evaluationMethod)) {
                candidate.setPrediction(predictViaLlm(candidate, context, goal));
            } else {
                log.debug("[SAND] Tool '{}' does not implement SimulatableTool; candidate #{} keeps neutral " +
                                "default score={} (evaluationMethod=simulation)",
                        candidate.getToolName(), candidate.getIndex(), candidate.getScore());
            }
        }
    }

    private String predictViaLlm(SandCandidate candidate, String context, String goal) {
        try {
            String prompt = promptLoader.load(predictPromptPath, Map.of(
                    "toolName", candidate.getToolName(),
                    "parameters", writeJson(candidate.getParameters()),
                    "goal", goal,
                    "context", context
            ));
            String response = chatClient.prompt(new Prompt(List.of(new UserMessage(prompt)))).call().content();
            Map<String, Object> parsed = parseJsonObject(response);
            return String.valueOf(parsed.getOrDefault("predicted_result", response));
        } catch (Exception e) {
            log.debug("[SAND] LLM predict failed for {}: {}", candidate.getToolName(), e.getMessage());
            return "";
        }
    }

    private void scoreAll(List<SandCandidate> candidates, List<Tool> tools, String goal, String context) {
        if ("simulation".equalsIgnoreCase(evaluationMethod)) return;
        Map<String, Tool> toolMap = tools.stream().collect(Collectors.toMap(Tool::getName, t -> t));
        for (SandCandidate candidate : candidates) {
            try {
                String prompt = promptLoader.load(evaluatePromptPath, Map.of(
                        "toolName", candidate.getToolName(),
                        "parameters", writeJson(candidate.getParameters()),
                        "prediction", candidate.getPrediction() != null ? candidate.getPrediction() : "",
                        "goal", goal,
                        "context", context
                ));
                String response = chatClient.prompt(new Prompt(List.of(new UserMessage(prompt)))).call().content();
                Map<String, Object> parsed = parseJsonObject(response);
                double score = ((Number) parsed.getOrDefault("score", 0.5)).doubleValue();
                candidate.setScore(Math.max(0.0, Math.min(1.0, score)));
            } catch (Exception e) {
                log.debug("[SAND] scoreAll failed for {}: {}", candidate.getToolName(), e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void storeTrainingEntry(SessionState session, int stepIndex, String goal,
                                    String contextSummary, List<SandCandidate> candidates, int selectedIndex) {
        List<Map<String, Object>> log_ = (List<Map<String, Object>>) session.getAttributes()
                .computeIfAbsent(StrategySessionKeys.SAND_TRAINING_LOG, k -> new ArrayList<>());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stepIndex", stepIndex);
        entry.put("goal", goal);
        entry.put("contextSummary", contextSummary);
        entry.put("selectedIndex", selectedIndex);
        List<Map<String, Object>> candidateData = new ArrayList<>();
        for (SandCandidate c : candidates) {
            Map<String, Object> cd = new LinkedHashMap<>();
            cd.put("index", c.getIndex());
            cd.put("toolName", c.getToolName());
            cd.put("parameters", c.getParameters());
            cd.put("reasoning", c.getReasoning());
            cd.put("prediction", c.getPrediction());
            cd.put("fromSimulation", c.isFromSimulation());
            cd.put("score", c.getScore());
            candidateData.add(cd);
        }
        entry.put("candidates", candidateData);
        log_.add(entry);
        while (log_.size() > maxTrainingLogEntries) {
            log_.remove(0);
        }
    }

    private String extractGoal(SessionState session, IntentAnalysisResult intent) {
        if (session.getPlanState() != null && session.getPlanState().getGoalDescription() != null) {
            return session.getPlanState().getGoalDescription();
        }
        return intent.getIntents() != null && !intent.getIntents().isEmpty()
                ? intent.getIntents().get(0).getName() : "unknown";
    }

    private String buildContextSummary(SessionState session) {
        var messages = session.getMessages();
        int fromIdx = Math.max(0, messages.size() - 5);
        StringBuilder sb = new StringBuilder();
        for (int i = fromIdx; i < messages.size(); i++) {
            var msg = messages.get(i);
            sb.append(msg.getRole().name()).append(": ")
                    .append(truncate(msg.getContent(), 300)).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatTools(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonArray(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();
            return objectMapper.readValue(cleaned.substring(start, end + 1),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) return Map.of();
            return objectMapper.readValue(cleaned.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
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

    private String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "...";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
