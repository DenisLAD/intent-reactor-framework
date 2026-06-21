package com.intentreactor.core.preprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Intent;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.IntentPreprocessor;
import com.intentreactor.api.Message;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DefaultIntentPreprocessor implements IntentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntentPreprocessor.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final IntentReactorProperties properties;
    private final PromptLoader promptLoader = new PromptLoader();

    public DefaultIntentPreprocessor(ChatClient chatClient,
                                     ObjectMapper objectMapper,
                                     IntentReactorProperties properties) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public IntentAnalysisResult analyze(String message, SessionState sessionState, Map<String, Object> context) {
        String systemPrompt = promptLoader.load(properties.getLlm().getPromptResources().getIntent(), context);
        List<org.springframework.ai.chat.messages.Message> messages = buildMessages(systemPrompt, message, sessionState);

        int maxRetries = properties.getPlanning().getMaxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String response = chatClient.prompt(new Prompt(messages)).call().content();
                IntentAnalysisResult result = parseResponse(response);
                return result;
            } catch (Exception e) {
                log.warn("Intent analysis attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("All intent analysis attempts exhausted, returning uncertain result");
                    IntentAnalysisResult fallback = new IntentAnalysisResult();
                    fallback.setUncertain(true);
                    fallback.setReasoningSuggestion(message);
                    fallback.setIntents(List.of(new Intent(message, 1.0, Map.of())));
                    return fallback;
                }
                messages = appendRetryInstruction(messages);
            }
        }
        // unreachable
        IntentAnalysisResult fallback = new IntentAnalysisResult();
        fallback.setUncertain(true);
        fallback.setIntents(List.of(new Intent("unknown", 1.0, Map.of())));
        return fallback;
    }

    protected List<org.springframework.ai.chat.messages.Message> buildMessages(
            String systemPrompt, String userMessage, SessionState session) {

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        int maxHistory = properties.getIntent().getPreprocessor().getMaxHistory();
        List<Message> history = session.getMessages();
        int startIdx = Math.max(0, history.size() - maxHistory);
        for (int i = startIdx; i < history.size(); i++) {
            Message m = history.get(i);
            if (m.getRole() == Message.Role.USER) {
                messages.add(new UserMessage(m.getContent()));
            } else if (m.getRole() == Message.Role.SYSTEM) {
                // Tool results stored as SYSTEM in session must be sent as UserMessage,
                // consistent with DefaultReACTPlanner.buildMessages() behavior.
                messages.add(new UserMessage(m.getContent()));
            } else {
                messages.add(new org.springframework.ai.chat.messages.AssistantMessage(m.getContent()));
            }
        }

        messages.add(new UserMessage(userMessage));
        return messages;
    }

    @SuppressWarnings("unchecked")
    protected IntentAnalysisResult parseResponse(String response) throws Exception {
        String json = extractJson(response);
        IntentAnalysisResult result = objectMapper.readValue(json, IntentAnalysisResult.class);
        Map<String, Object> raw = objectMapper.readValue(json, Map.class);
        result.setRawLLMOutput(raw);
        return result;
    }

    private String extractJson(String response) {
        if (response == null) throw new IllegalArgumentException("Empty LLM response");
        List<String> candidates = extractAllJsonCandidates(stripMarkdownFences(response));
        if (candidates.isEmpty())
            throw new IllegalArgumentException("No JSON object found in response: " + response);
        // Prefer the candidate that contains intent analysis keys.
        for (String candidate : candidates) {
            try {
                Map<?, ?> m = objectMapper.readValue(candidate, Map.class);
                if (m.containsKey("intents") || m.containsKey("reasoningSuggestion")) return candidate;
            } catch (Exception ignored) {
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private String stripMarkdownFences(String response) {
        String s = response.strip();
        if (!s.startsWith("```")) return s;
        int newline = s.indexOf('\n');
        if (newline < 0) return s;
        s = s.substring(newline + 1);
        int closingFence = s.lastIndexOf("```");
        if (closingFence >= 0) s = s.substring(0, closingFence);
        return s.strip();
    }

    private List<String> extractAllJsonCandidates(String response) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        for (int i = 0; i < response.length(); i++) {
            char c = response.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped character
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}' && depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        result.add(response.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return result;
    }

    private List<org.springframework.ai.chat.messages.Message> appendRetryInstruction(
            List<org.springframework.ai.chat.messages.Message> messages) {
        List<org.springframework.ai.chat.messages.Message> updated = new ArrayList<>(messages);
        updated.add(new UserMessage(
                promptLoader.load(properties.getLlm().getPromptResources().getIntentRetry())));
        return updated;
    }
}
