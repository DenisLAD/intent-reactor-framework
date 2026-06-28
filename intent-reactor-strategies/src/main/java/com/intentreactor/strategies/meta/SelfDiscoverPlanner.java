package com.intentreactor.strategies.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
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

/**
 * Self-Discover planner: before solving, the model selects relevant reasoning modules from a
 * curated bank, adapts them to the specific task, then executes using the structured plan.
 * <p>
 * Prompt files configured via intent-reactor.planning.strategies.prompts.*
 * <p>
 * Three phases: SELECT → ADAPT → EXECUTE (delegate)
 * <p>
 * Activate with: intent-reactor.planning.strategy: self-discover
 */
public class SelfDiscoverPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(SelfDiscoverPlanner.class);

    private static final String PHASE_KEY   = StrategySessionKeys.SD_PHASE;
    private static final String MODULES_KEY = StrategySessionKeys.SD_MODULES;
    private static final String PLAN_KEY    = StrategySessionKeys.SD_PLAN;

    private final Planner delegate;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int moduleCount;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String selectPromptPath;
    private final String adaptPromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public SelfDiscoverPlanner(Planner delegate, ChatClient chatClient, ObjectMapper objectMapper,
                               StrategiesProperties props) {
        this.delegate = delegate;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.moduleCount = props.getSelfDiscover().getModuleCount();
        this.selectPromptPath = props.getPrompts().getSelfDiscoverSelect();
        this.adaptPromptPath = props.getPrompts().getSelfDiscoverAdapt();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "SELECT");
        String goal = getGoal(session);

        return switch (phase) {
            case "SELECT" -> selectModules(session, goal, intent);
            case "ADAPT" -> adaptModules(session, goal, intent);
            case "EXECUTE" -> delegate.plan(session, intent);
            default -> selectModules(session, goal, intent);
        };
    }

    private Plan selectModules(SessionState session, String goal, IntentAnalysisResult intent) {
        String modulesList = String.join("\n", ReasoningModuleBank.MODULES.stream()
                .map(m -> "- " + m).toList());

        try {
            String systemPrompt = promptLoader.load(selectPromptPath,
                    Map.of("count", moduleCount, "modules", modulesList));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(labels.getTask() + goal)
            ))).call().content();

            List<String> selected = parseStringArray(response);
            if (selected.isEmpty()) selected = ReasoningModuleBank.MODULES.subList(0, Math.min(moduleCount, 3));

            session.getAttributes().put(MODULES_KEY, selected);
            session.getAttributes().put(PHASE_KEY, "ADAPT");

            log.debug("[SelfDiscover] Selected {} modules for session {}", selected.size(), session.getId());
            return adaptModules(session, goal, intent);

        } catch (Exception e) {
            log.warn("[SelfDiscover] Module selection failed: {}", e.getMessage());
            session.getAttributes().put(PHASE_KEY, "EXECUTE");
            return delegate.plan(session, intent);
        }
    }

    @SuppressWarnings("unchecked")
    private Plan adaptModules(SessionState session, String goal, IntentAnalysisResult intent) {
        List<String> selected = (List<String>) session.getAttributes().getOrDefault(
                MODULES_KEY, ReasoningModuleBank.MODULES.subList(0, 3));

        String modulesList = String.join("\n", selected.stream().map(m -> "- " + m).toList());

        try {
            String systemPrompt = promptLoader.load(adaptPromptPath,
                    Map.of("modules", modulesList));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(labels.getTask() + goal)
            ))).call().content();

            String structuredPlan = response.strip();
            session.getAttributes().put(PLAN_KEY, structuredPlan);
            session.getAttributes().put(PHASE_KEY, "EXECUTE");

            session.addMessage(Message.system(labels.getStructuredPlan() + structuredPlan));

            log.debug("[SelfDiscover] Adapted structured plan for session {}", session.getId());
            return delegate.plan(session, intent);

        } catch (Exception e) {
            log.warn("[SelfDiscover] Module adaptation failed: {}", e.getMessage());
            session.getAttributes().put(PHASE_KEY, "EXECUTE");
            return delegate.plan(session, intent);
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
            return List.of();
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
