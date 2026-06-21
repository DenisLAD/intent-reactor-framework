package com.intentreactor.strategies.refinement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * Step-Back Prompting decorator: before delegating to the base planner, makes one LLM call to
 * abstract the specific question into general principles/background knowledge, then injects that
 * knowledge into the session. This primes the model with relevant context before it starts planning.
 * <p>
 * Pattern: Abstract → Answer abstract question → Solve specific question with enriched context.
 * <p>
 * Activate with: intent-reactor.planning.strategy: step-back
 */
public class StepBackPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(StepBackPlanner.class);
    private static final String STEP_BACK_DONE_KEY = "step_back_done";

    private static final String ABSTRACT_SYSTEM_PROMPT =
            "Ты эксперт по абстрактному мышлению. Твоя задача — к конкретному вопросу или задаче " +
                    "подобрать более общий принцип, концепцию или фоновые знания, которые помогут её решить. " +
                    "Отвечай кратко и по существу — только ключевые принципы, без лишних слов.";

    private static final String ABSTRACT_USER_TEMPLATE =
            "Задача: {goal}\n\n" +
                    "Какие общие принципы, концепции или фоновые знания наиболее релевантны для решения этой задачи? " +
                    "Дай краткий, но исчерпывающий ответ.";

    private final Planner delegate;
    private final ChatClient chatClient;
    private final PromptLoader promptLoader = new PromptLoader();

    public StepBackPlanner(Planner delegate, ChatClient chatClient,
                           IntentReactorProperties properties, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.chatClient = chatClient;
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(STEP_BACK_DONE_KEY))) {
            String goal = intent.getIntents().isEmpty() ? "unknown" : intent.getIntents().get(0).getName();
            try {
                String userPrompt = ABSTRACT_USER_TEMPLATE.replace("{goal}", goal);
                String background = chatClient.prompt(new Prompt(List.of(
                        new SystemMessage(ABSTRACT_SYSTEM_PROMPT),
                        new UserMessage(userPrompt)
                ))).call().content();

                session.addMessage(Message.system("[BACKGROUND KNOWLEDGE]\n" + background));
                log.debug("[Step-Back] Generated background for session {}: {}", session.getId(), background);
            } catch (Exception e) {
                log.warn("[Step-Back] Failed to generate background knowledge: {}", e.getMessage());
            }
            session.getAttributes().put(STEP_BACK_DONE_KEY, true);
        }
        return delegate.plan(session, intent);
    }
}
