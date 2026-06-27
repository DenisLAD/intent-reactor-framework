package com.intentreactor.core.planner;

import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.MessageMarkers;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

/**
 * Decorator over {@link DefaultReACTPlanner} that appends a {@code [REFLECTION]} critique
 * to the session history after each Observe step, guiding the LLM to self-correct
 * before the next Reason cycle.
 */
public class ReflexionPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(ReflexionPlanner.class);
    private static final String REFLEXION_COUNT_KEY = "reflexionCount";

    private final Planner delegate;
    private final ChatClient chatClient;
    private final IntentReactorProperties properties;
    private final PromptLoader promptLoader = new PromptLoader();

    public ReflexionPlanner(Planner delegate, ChatClient chatClient, IntentReactorProperties properties) {
        this.delegate = delegate;
        this.chatClient = chatClient;
        this.properties = properties;
    }

    @Override
    public Plan plan(SessionState sessionState, IntentAnalysisResult intent) {
        maybeGenerateReflection(sessionState);
        return delegate.plan(sessionState, intent);
    }

    private void maybeGenerateReflection(SessionState session) {
        int maxReflections = properties.getPlanning().getReflexion().getMaxReflectionSteps();
        int currentCount = (int) session.getAttributes().getOrDefault(REFLEXION_COUNT_KEY, 0);

        if (currentCount >= maxReflections) return;

        List<Message> messages = session.getMessages();
        if (!hasRecentFailure(messages)) return;

        try {
            String reflection = generateReflection(session);
            session.addMessage(Message.system(MessageMarkers.REFLECTION + " " + reflection));
            session.addMessage(Message.system(MessageMarkers.HINT + " Previous action failed. Based on the reflection above, try a different approach or a different tool."));
            session.getAttributes().put(REFLEXION_COUNT_KEY, currentCount + 1);
            log.debug("Generated reflection #{} for session {}", currentCount + 1, session.getId());
        } catch (Exception e) {
            log.warn("Failed to generate reflection for session {}: {}", session.getId(), e.getMessage());
        }
    }

    private boolean hasRecentFailure(List<Message> messages) {
        if (messages.isEmpty()) return false;
        // Check the last 5 messages regardless of role: [HINT] or other SYSTEM messages
        // added after [TOOL_ERROR] would previously mask the failure with reduce((a,b)->b).
        int checkCount = Math.min(5, messages.size());
        return messages.subList(messages.size() - checkCount, messages.size()).stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains(MessageMarkers.TOOL_ERROR));
    }

    private String generateReflection(SessionState session) {
        StringBuilder context = new StringBuilder("Original request and history:\n");
        List<Message> messages = session.getMessages();
        int maxHistory = properties.getPlanning().getContextWindow().getMaxMessages();
        int windowSize = maxHistory > 0 ? maxHistory : 10;
        int start = Math.max(0, messages.size() - windowSize);
        int maxChars = properties.getPlanning().getContextWindow().getMaxMessageChars();
        String truncSuffix = properties.getPlanning().getContextWindow().getTruncationSuffix();
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            String content = m.getContent();
            if (maxChars > 0 && content != null && content.length() > maxChars) {
                content = content.substring(0, maxChars) + truncSuffix;
            }
            context.append(m.getRole()).append(": ").append(content).append("\n");
        }

        IntentReactorProperties.PromptResources pr = properties.getLlm().getPromptResources();
        String systemPrompt = promptLoader.load(pr.getReflexionSystem());
        String userPrompt = promptLoader.load(pr.getReflexionUser(), Map.of("context", context.toString()));

        return chatClient.prompt(
                new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt)))
        ).call().content();
    }
}
