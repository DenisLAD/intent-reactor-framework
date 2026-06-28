package com.intentreactor.strategies.refinement;

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
 * Step-Back Prompting decorator: before delegating to the base planner, makes one LLM call to
 * abstract the specific question into general principles/background knowledge, then injects that
 * knowledge into the session.
 * <p>
 * Prompt files configured via intent-reactor.planning.strategies.prompts.*
 * <p>
 * Activate with: intent-reactor.planning.strategy: step-back
 */
public class StepBackPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(StepBackPlanner.class);
    private static final String STEP_BACK_DONE_KEY = StrategySessionKeys.STEP_BACK_DONE;

    private final Planner delegate;
    private final ChatClient chatClient;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String abstractPromptPath;
    private final String userPromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public StepBackPlanner(Planner delegate, ChatClient chatClient,
                           StrategiesProperties strategiesProperties) {
        this.delegate = delegate;
        this.chatClient = chatClient;
        this.abstractPromptPath = strategiesProperties.getPrompts().getStepBackAbstract();
        this.userPromptPath = strategiesProperties.getPrompts().getStepBackUser();
        this.labels = strategiesProperties.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(STEP_BACK_DONE_KEY))) {
            String goal = session.getPlanState() != null && session.getPlanState().getGoalDescription() != null
                    ? session.getPlanState().getGoalDescription() : "unknown";
            try {
                String systemPrompt = promptLoader.load(abstractPromptPath, Map.of());
                String userPrompt = promptLoader.load(userPromptPath, Map.of("goal", goal));

                String background = chatClient.prompt(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt)
                ))).call().content();

                session.addMessage(Message.system(labels.getBackgroundKnowledge() + background));
                log.debug("[Step-Back] Generated background for session {}: {}", session.getId(), background);
            } catch (Exception e) {
                log.warn("[Step-Back] Failed to generate background knowledge: {}", e.getMessage());
            }
            session.getAttributes().put(STEP_BACK_DONE_KEY, true);
        }
        return delegate.plan(session, intent);
    }
}
