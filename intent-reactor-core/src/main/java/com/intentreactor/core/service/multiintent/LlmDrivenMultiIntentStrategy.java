package com.intentreactor.core.service.multiintent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Intent;
import com.intentreactor.api.MultiIntentContext;
import com.intentreactor.api.MultiIntentStrategy;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SingleIntentExecutor;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LlmDrivenMultiIntentStrategy implements MultiIntentStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmDrivenMultiIntentStrategy.class);

    private final MultiIntentStrategy sequential;
    private final ChatClient chatClient;
    private final IntentReactorProperties properties;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader = new PromptLoader();

    public LlmDrivenMultiIntentStrategy(MultiIntentStrategy sequential,
                                        ChatClient chatClient,
                                        IntentReactorProperties properties,
                                        ObjectMapper objectMapper) {
        this.sequential = sequential;
        this.chatClient = chatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "llm-driven";
    }

    @Override
    public ReactorResponse execute(SessionState session, MultiIntentContext ctx,
                                   boolean persistent, SingleIntentExecutor executor) {
        List<Intent> ordered = orderIntentsWithLlm(ctx.getPendingIntents());
        ctx.setPendingIntents(new ArrayList<>(ordered));
        return sequential.execute(session, ctx, persistent, executor);
    }

    private List<Intent> orderIntentsWithLlm(List<Intent> intents) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < intents.size(); i++) {
                Intent it = intents.get(i);
                sb.append(i + 1).append(". ").append(it.getName())
                        .append(" (confidence: ").append(it.getConfidence()).append(")");
                if (it.getAttributes() != null && !it.getAttributes().isEmpty()) {
                    sb.append(", attributes: ").append(it.getAttributes());
                }
                sb.append("\n");
            }
            String prompt = promptLoader.load(
                    properties.getLlm().getPromptResources().getLlmDrivenOrdering(),
                    Map.of("intents", sb.toString()));
            String response = chatClient.prompt(
                    new Prompt(List.of(new UserMessage(prompt)))).call().content();
            return parseOrderedIntentNames(response, intents);
        } catch (Exception e) {
            log.warn("LLM-driven ordering failed, falling back to confidence sort: {}", e.getMessage());
            List<Intent> fallback = new ArrayList<>(intents);
            fallback.sort(Comparator.comparingDouble(Intent::getConfidence).reversed());
            return fallback;
        }
    }

    private List<Intent> parseOrderedIntentNames(String response, List<Intent> original) throws Exception {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end < 0) throw new IllegalArgumentException("No JSON array in LLM response");
        Map<String, Intent> byName = new LinkedHashMap<>();
        original.forEach(i -> byName.put(i.getName(), i));
        List<String> names = objectMapper.readValue(response.substring(start, end + 1),
                new TypeReference<List<String>>() {});
        List<Intent> ordered = new ArrayList<>();
        for (String name : names) {
            Intent found = byName.remove(name);
            if (found != null) ordered.add(found);
        }
        ordered.addAll(byName.values()); // intents missed by LLM → append at end
        return ordered;
    }
}
