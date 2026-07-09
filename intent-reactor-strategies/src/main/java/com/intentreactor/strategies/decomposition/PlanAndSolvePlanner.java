package com.intentreactor.strategies.decomposition;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Plan-and-Solve planner: generates a complete step-by-step plan in a single LLM call,
 * then executes each step sequentially.
 * <p>
 * Prompt file configured via intent-reactor.planning.strategies.prompts.plan-and-solve-plan
 * <p>
 * Activate with: intent-reactor.planning.strategy: plan-and-solve
 */
public class PlanAndSolvePlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(PlanAndSolvePlanner.class);

    private static final String PHASE_KEY = StrategySessionKeys.PAS_PHASE;
    private static final String PLAN_KEY = StrategySessionKeys.PAS_PLAN;
    private static final String STEP_KEY = StrategySessionKeys.PAS_STEP;
    private static final String GOAL_KEY = StrategySessionKeys.PAS_GOAL;
    private static final String MSG_START_KEY = StrategySessionKeys.PAS_MSG_START;

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int maxPlanSteps;
    private final boolean autonomous;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String planPromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public PlanAndSolvePlanner(ChatClient chatClient, ToolProvider toolProvider,
                               ObjectMapper objectMapper, StrategiesProperties props,
                               IntentReactorProperties intentReactorProperties) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.maxPlanSteps = props.getPlanAndSolve().getMaxPlanSteps();
        this.autonomous = intentReactorProperties.getPlanning().isAutonomous();
        this.planPromptPath = props.getPrompts().getPlanAndSolvePlan();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "PLANNING");
        String goal = getGoal(session);

        return switch (phase) {
            case "PLANNING" -> generatePlan(session, intent, goal);
            case "EXECUTING" -> executeNextStep(session);
            default -> generatePlan(session, intent, goal);
        };
    }

    private Plan generatePlan(SessionState session, IntentAnalysisResult intent, String goal) {
        List<Tool> tools = toolProvider.getAvailableTools(session);
        String toolsList = tools.stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));

        String systemPrompt = promptLoader.load(planPromptPath,
                Map.of("tools", toolsList, "max", maxPlanSteps));

        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(labels.getTask() + goal)
            ))).call().content();

            List<Map<String, Object>> steps = parseSteps(response);
            if (steps.size() > maxPlanSteps) steps = steps.subList(0, maxPlanSteps);
            if (steps.isEmpty()) {
                return new SimplePlan(List.of(SimplePlanStep.fail("Could not generate a plan for: " + goal)));
            }

            session.getAttributes().put(GOAL_KEY, goal);
            session.getAttributes().put(PLAN_KEY, steps);
            session.getAttributes().put(STEP_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "EXECUTING");
            session.getAttributes().put(MSG_START_KEY, session.getMessages().size());

            log.debug("[PlanAndSolve] Generated {}-step plan for session {}", steps.size(), session.getId());
            return executeNextStep(session);

        } catch (Exception e) {
            log.warn("[PlanAndSolve] Plan generation failed: {}", e.getMessage());
            return new SimplePlan(List.of(SimplePlanStep.fail("Plan generation failed: " + e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private Plan executeNextStep(SessionState session) {
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) session.getAttributes().get(PLAN_KEY);
        int stepIndex = (int) session.getAttributes().getOrDefault(STEP_KEY, 0);

        if (steps == null || stepIndex >= steps.size()) {
            return synthesizeFinal(session);
        }

        Map<String, Object> step = steps.get(stepIndex);
        String toolName = (String) step.get("toolName");
        String description = (String) step.getOrDefault("description", "Step " + (stepIndex + 1));
        Map<String, Object> parameters = (Map<String, Object>) step.getOrDefault("parameters", Map.of());

        session.getAttributes().put(STEP_KEY, stepIndex + 1);
        log.debug("[PlanAndSolve] Executing step {}/{} for session {}", stepIndex + 1, steps.size(), session.getId());

        boolean isLastStep = stepIndex + 1 >= steps.size();

        if (toolName == null || toolName.isBlank()) {
            if (isLastStep) {
                return synthesizeFinal(session);
            }
            return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null, description, false)));
        }

        List<Tool> tools = toolProvider.getAvailableTools(session);
        boolean toolExists = tools.stream().anyMatch(t -> t.getName().equals(toolName));
        boolean isRisky = tools.stream().anyMatch(t -> t.getName().equals(toolName) && t.isRisky());
        boolean needsConfirmation = isRisky && !autonomous;

        if (!toolExists) {
            session.getAttributes().put(STEP_KEY, stepIndex + 1);
            return executeNextStep(session);
        }

        Action action = new SimpleAction(toolName, parameters);
        return new SimplePlan(List.of(SimplePlanStep.act(action, description, needsConfirmation)));
    }

    private Plan synthesizeFinal(SessionState session) {
        String goal = (String) session.getAttributes().getOrDefault(GOAL_KEY, "");
        int msgStart = (int) session.getAttributes().getOrDefault(MSG_START_KEY, 0);

        StringBuilder results = new StringBuilder();
        List<Message> allMessages = session.getMessages();
        for (int i = msgStart; i < allMessages.size(); i++) {
            Message m = allMessages.get(i);
            if (m.getRole() == Message.Role.SYSTEM) {
                results.append(m.getContent()).append("\n");
            }
        }

        try {
            String finalAnswer = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage("Ты помощник. Сформулируй чёткий финальный ответ на задачу пользователя, опираясь на результаты выполненных шагов."),
                    new UserMessage(labels.getTask() + goal + "\n\nРезультаты шагов:\n" + results)
            ))).call().content();
            log.debug("[PlanAndSolve] Synthesized final answer for session {}", session.getId());
            return new SimplePlan(List.of(SimplePlanStep.done(finalAnswer)));
        } catch (Exception e) {
            log.warn("[PlanAndSolve] Synthesis failed: {}", e.getMessage());
            // Fallback: last SYSTEM message from the current planning cycle only
            for (int i = allMessages.size() - 1; i >= msgStart; i--) {
                if (allMessages.get(i).getRole() == Message.Role.SYSTEM) {
                    return new SimplePlan(List.of(SimplePlanStep.done(allMessages.get(i).getContent())));
                }
            }
            return new SimplePlan(List.of(SimplePlanStep.done(results.toString().trim())));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(String response) {
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
