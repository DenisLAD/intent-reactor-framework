package com.intentreactor.strategies.refinement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.StepType;
import com.intentreactor.core.util.PromptLoader;
import com.intentreactor.strategies.config.StrategiesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

/**
 * Generator+Critic Reflection decorator: when the delegate produces a DONE step, this planner
 * invokes a critic LLM to evaluate the response quality. If the critic score is below the
 * satisfaction threshold (and max iterations not reached), the critique is injected into the
 * session and the delegate is asked to improve its answer.
 * <p>
 * Prompt files configured via intent-reactor.planning.strategies.prompts.*
 * <p>
 * Activate with: intent-reactor.planning.strategy: reflection
 */
public class ReflectionPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(ReflectionPlanner.class);
    private static final String REFLECTION_COUNT_KEY = "reflection_count";
    private static final String BASE_CRITIQUE = "classpath:prompts/strategies/reflection-critique.md";

    private final Planner delegate;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int maxIterations;
    private final double satisfactionThreshold;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String userPromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public ReflectionPlanner(Planner delegate, ChatClient chatClient, ObjectMapper objectMapper,
                             StrategiesProperties strategiesProperties) {
        this.delegate = delegate;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.maxIterations = strategiesProperties.getReflection().getMaxIterations();
        this.satisfactionThreshold = strategiesProperties.getReflection().getSatisfactionThreshold();
        this.userPromptPath = strategiesProperties.getPrompts().getReflectionUser();
        this.labels = strategiesProperties.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        Plan plan = delegate.plan(session, intent);

        if (plan.steps().isEmpty() || plan.steps().get(0).type() != StepType.DONE) {
            return plan;
        }

        int reflectionCount = (int) session.getAttributes().getOrDefault(REFLECTION_COUNT_KEY, 0);
        if (reflectionCount >= maxIterations) {
            log.debug("[Reflection] Max iterations ({}) reached for session {}", maxIterations, session.getId());
            return plan;
        }

        String finalAnswer = plan.steps().get(0).description();
        String goal = intent.getIntents().isEmpty() ? "" : intent.getIntents().get(0).getName();

        try {
            String criticSystem = promptLoader.load(BASE_CRITIQUE, Map.of());
            String userPrompt = promptLoader.load(userPromptPath, Map.of(
                    "goal", goal,
                    "answer", finalAnswer != null ? finalAnswer : ""
            ));

            String criticResponse = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(criticSystem),
                    new UserMessage(userPrompt)
            ))).call().content();

            CriticResult result = parseCriticResponse(criticResponse);

            if (result.satisfied || result.score >= satisfactionThreshold) {
                log.debug("[Reflection] Satisfied with score={} for session {}", result.score, session.getId());
                return plan;
            }

            session.addMessage(Message.system(
                    labels.getCritique() + result.score + "/1.0" +
                            labels.getWeaknesses() + result.critique +
                            labels.getImprovement() + result.improvement));
            session.getAttributes().put(REFLECTION_COUNT_KEY, reflectionCount + 1);
            log.debug("[Reflection] Iteration {}/{} for session {}: score={}", reflectionCount + 1, maxIterations, session.getId(), result.score);

            return delegate.plan(session, intent);

        } catch (Exception e) {
            log.warn("[Reflection] Critic evaluation failed: {}", e.getMessage());
            return plan;
        }
    }

    @SuppressWarnings("unchecked")
    private CriticResult parseCriticResponse(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
            CriticResult r = new CriticResult();
            r.score = ((Number) map.getOrDefault("score", 0.5)).doubleValue();
            r.satisfied = Boolean.TRUE.equals(map.get("satisfied"));
            r.critique = String.valueOf(map.getOrDefault("critique", ""));
            r.improvement = String.valueOf(map.getOrDefault("improvement", ""));
            return r;
        } catch (Exception e) {
            CriticResult r = new CriticResult();
            r.score = 0.5;
            r.satisfied = false;
            r.critique = response;
            r.improvement = "";
            return r;
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

    private static class CriticResult {
        double score;
        boolean satisfied;
        String critique;
        String improvement;
    }
}
