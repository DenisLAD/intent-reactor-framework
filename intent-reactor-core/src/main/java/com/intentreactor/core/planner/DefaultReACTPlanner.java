package com.intentreactor.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.MessageBuildContext;
import com.intentreactor.api.MessageContextPostProcessor;
import com.intentreactor.api.MessageContextPreProcessor;
import com.intentreactor.api.Plan;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Planner;
import com.intentreactor.api.PromptContextProvider;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.LlmResponseParser;
import com.intentreactor.core.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultReACTPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(DefaultReACTPlanner.class);
    @SuppressWarnings("unchecked")
    private static final Set<String> REACT_JSON_KEYS = Set.of("done", "failed", "toolName");
    final IntentReactorProperties properties;
    final PromptLoader promptLoader = new PromptLoader();
    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final List<PromptContextProvider> promptContextProviders;
    private final List<MessageContextPreProcessor> preProcessors;
    private final List<MessageContextPostProcessor> postProcessors;

    public DefaultReACTPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper) {
        this(chatClient, toolProvider, properties, objectMapper, List.of(), List.of(), List.of());
    }

    public DefaultReACTPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper,
                               List<PromptContextProvider> promptContextProviders) {
        this(chatClient, toolProvider, properties, objectMapper, promptContextProviders, List.of(), List.of());
    }

    /**
     * Backward-compatible constructor: wraps {@code messageCompressor} as a post-processor.
     */
    public DefaultReACTPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper,
                               List<PromptContextProvider> promptContextProviders,
                               MessageCompressor messageCompressor) {
        this(chatClient, toolProvider, properties, objectMapper, promptContextProviders,
                List.of(),
                messageCompressor != null ? List.of(messageCompressor) : List.of());
    }

    /**
     * Full constructor used by {@code IntentReactorAutoConfiguration}.
     */
    public DefaultReACTPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper,
                               List<PromptContextProvider> promptContextProviders,
                               List<MessageContextPreProcessor> preProcessors,
                               List<MessageContextPostProcessor> postProcessors) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.promptContextProviders = promptContextProviders != null ? promptContextProviders : List.of();
        this.preProcessors = preProcessors != null ? new ArrayList<>(preProcessors) : new ArrayList<>();
        List<MessageContextPostProcessor> sorted = postProcessors != null
                ? new ArrayList<>(postProcessors)
                : new ArrayList<>();
        sorted.sort(Comparator.comparingInt(Ordered::getOrder));
        this.postProcessors = Collections.unmodifiableList(sorted);
    }

    @Override
    public Plan plan(SessionState sessionState, IntentAnalysisResult intent) {
        List<Tool> tools = toolProvider.getAvailableTools(sessionState);

        if (sessionState.getPlanState() == null) {
            sessionState.setPlanState(new PlanState(deriveGoal(intent)));
        }

        PlanState planState = sessionState.getPlanState();

        if (planState.getCurrentStepIndex() >= properties.getPlanning().getMaxSteps()) {
            return new SimplePlan(List.of(SimplePlanStep.fail("Max steps exceeded")));
        }

        String systemPrompt = buildSystemPrompt(planState.getGoalDescription(), tools, sessionState);
        List<org.springframework.ai.chat.messages.Message> messages = buildMessages(systemPrompt, sessionState);

        int maxRetries = properties.getPlanning().getMaxRetries();
        List<org.springframework.ai.chat.messages.Message> retryMessages = new ArrayList<>(messages);
        String lastResponse = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String response = chatClient.prompt(new Prompt(retryMessages)).call().content();
                lastResponse = response;
                return parseResponse(response, tools, sessionState);
            } catch (IllegalArgumentException e) {
                log.warn("ReACT plan attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt < maxRetries) {
                    String bad = lastResponse != null ? lastResponse : "(no response)";
                    if (lastResponse != null) {
                        retryMessages.add(new org.springframework.ai.chat.messages.AssistantMessage(lastResponse));
                    }
                    retryMessages.add(new UserMessage(buildFormatCorrectionPrompt(bad, e.getMessage())));
                } else {
                    return new SimplePlan(List.of(SimplePlanStep.fail("Planning failed: " + e.getMessage())));
                }
            } catch (Exception e) {
                log.warn("ReACT plan attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt < maxRetries) {
                    if (lastResponse != null) {
                        retryMessages.add(new org.springframework.ai.chat.messages.AssistantMessage(lastResponse));
                        retryMessages.add(new UserMessage(buildFormatCorrectionPrompt(lastResponse, e.getMessage())));
                    }
                } else {
                    return new SimplePlan(List.of(SimplePlanStep.fail("Planning failed: " + e.getMessage())));
                }
            }
        }
        return new SimplePlan(List.of(SimplePlanStep.fail("Planning failed")));
    }

    protected String buildSystemPrompt(String goal, List<Tool> tools, SessionState session) {
        String templatePath = properties.getLlm().getPromptResources().getSystem();
        Map<String, Object> vars = new HashMap<>();
        vars.put("goal", goal != null ? goal : "");
        vars.put("tools", buildToolsDescription(tools));
        for (PromptContextProvider provider : promptContextProviders) {
            vars.putAll(provider.getAdditionalVariables(session));
        }
        return promptLoader.load(templatePath, vars);
    }

    private String buildToolsDescription(List<Tool> tools) {
        return LlmResponseParser.formatTools(tools, objectMapper);
    }

    protected List<org.springframework.ai.chat.messages.Message> buildMessages(
            String systemPrompt, SessionState session) {

        IntentReactorProperties.ContextWindowConfig ctx = properties.getPlanning().getContextWindow();
        int maxMsgs = ctx.getMaxMessages();
        int maxChars = ctx.getMaxMessageChars();
        String suffix = ctx.getTruncationSuffix();

        // 1. Pre-processors on the full session message list (run before windowing).
        List<Message> all = session.getMessages();
        if (!preProcessors.isEmpty()) {
            List<Message> filtered = new ArrayList<>(all);
            for (MessageContextPreProcessor pre : preProcessors) {
                filtered = pre.process(filtered, session);
            }
            all = filtered;
        }

        // 2. Sliding window: keep last maxMsgs messages + re-insert evicted pinned messages.
        List<Message> windowed;
        List<Message> evicted = List.of();
        if (maxMsgs > 0 && all.size() > maxMsgs) {
            List<Message> tail = new ArrayList<>(all.subList(all.size() - maxMsgs, all.size()));

            // Collect pinned messages that were evicted from the tail (identity-based check).
            List<Message> missingPinned = new ArrayList<>();
            for (Message m : all) {
                if (!m.isPinned()) continue;
                boolean inTail = false;
                for (Message t : tail) {
                    if (t == m) {
                        inTail = true;
                        break;
                    }
                }
                if (!inTail) missingPinned.add(m);
            }

            if (!missingPinned.isEmpty()) {
                // Re-insert in original chronological order using identity positions.
                java.util.IdentityHashMap<Message, Integer> idx = new java.util.IdentityHashMap<>();
                for (int i = 0; i < all.size(); i++) idx.put(all.get(i), i);
                List<Message> merged = new ArrayList<>(missingPinned.size() + tail.size());
                merged.addAll(missingPinned);
                merged.addAll(tail);
                merged.sort(java.util.Comparator.comparingInt(m -> idx.getOrDefault(m, Integer.MAX_VALUE)));
                windowed = merged;
            } else {
                windowed = tail;
            }
            log.debug("[buildMessages] sliding window: kept {}/{} messages ({} pinned re-inserted)",
                    windowed.size(), all.size(), missingPinned.size());

            // Evicted = messages outside the window, excluding pinned (they're re-inserted).
            List<Message> outsideWindow = new ArrayList<>(all.subList(0, all.size() - maxMsgs));
            outsideWindow.removeIf(Message::isPinned);
            evicted = Collections.unmodifiableList(outsideWindow);
        } else {
            windowed = all;
        }

        // 3. Post-processors (deduplication, compression, etc.) in order.
        MessageBuildContext context = new MessageBuildContext(evicted, session);
        List<Message> processed = windowed;
        for (MessageContextPostProcessor post : postProcessors) {
            processed = post.process(processed, context);
        }

        // 4. Convert to Spring AI types, applying per-message char limits from context.
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        for (Message m : processed) {
            String content = m.getContent();
            int limit = context.getCharLimit(m, maxChars);
            if (limit > 0 && content != null && content.length() > limit) {
                content = content.substring(0, limit) + suffix;
                log.debug("[buildMessages] truncated message to {} chars", limit);
            }
            switch (m.getRole()) {
                case USER -> messages.add(new UserMessage(content));
                case ASSISTANT -> messages.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
                // Tool results stored as SYSTEM in session must be sent as UserMessage for LLMs.
                case SYSTEM -> messages.add(new UserMessage(content));
            }
        }

        // 5. Chat models require the conversation to end with a user message.
        boolean endsWithUser = !messages.isEmpty()
                && messages.get(messages.size() - 1) instanceof UserMessage;
        if (!endsWithUser) {
            String goal = (session.getPlanState() != null && session.getPlanState().getGoalDescription() != null)
                    ? session.getPlanState().getGoalDescription()
                    : "Please proceed.";
            messages.add(new UserMessage(goal));
            log.debug("[buildMessages] injected UserMessage(goal='{}'), total messages: {}", goal, messages.size());
        }

        log.debug("[buildMessages] final count={}, types={}",
                messages.size(),
                messages.stream().map(m -> m.getMessageType().getValue()).toList());

        return messages;
    }

    Plan parseResponse(String response, List<Tool> tools, SessionState session) throws Exception {
        String json = LlmResponseParser.extractJson(response, objectMapper, REACT_JSON_KEYS);
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        String thought = (String) parsed.get("thought");

        if (Boolean.TRUE.equals(parsed.get("done"))) {
            String finalMessage = (String) parsed.get("finalMessage");
            if (finalMessage == null || finalMessage.isBlank()) {
                finalMessage = thought;
            }
            if (finalMessage == null || finalMessage.isBlank()) {
                throw new IllegalArgumentException("DONE response is missing 'finalMessage' field");
            }
            List<PlanStep> steps = new ArrayList<>();
            if (thought != null && !thought.isBlank()) steps.add(SimplePlanStep.reason(thought));
            steps.add(SimplePlanStep.done(finalMessage));
            return new SimplePlan(steps);
        }

        if (Boolean.TRUE.equals(parsed.get("failed"))) {
            String reason = (String) parsed.getOrDefault("reason", "Planning failed");
            List<PlanStep> steps = new ArrayList<>();
            if (thought != null && !thought.isBlank()) steps.add(SimplePlanStep.reason(thought));
            steps.add(SimplePlanStep.fail(reason));
            return new SimplePlan(steps);
        }

        String toolName = (String) parsed.get("toolName");
        if (toolName == null) {
            throw new IllegalArgumentException("Response missing 'toolName': " + json);
        }

        Map<String, Object> params = parsed.containsKey("parameters")
                ? (Map<String, Object>) parsed.get("parameters")
                : Map.of();

        Tool tool = tools.stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));

        boolean needsConfirmation = tool.isRisky() && !properties.getPlanning().isAutonomous();
        Action action = new SimpleAction(toolName, params);
        List<PlanStep> steps = new ArrayList<>();
        if (thought != null && !thought.isBlank()) steps.add(SimplePlanStep.reason(thought));
        steps.add(SimplePlanStep.act(action, "Execute " + toolName, needsConfirmation));
        return new SimplePlan(steps);
    }

    private String buildFormatCorrectionPrompt(String badResponse, String error) {
        String templatePath = properties.getLlm().getPromptResources().getFormatCorrection();
        return promptLoader.load(templatePath, Map.of(
                "error", error != null ? error : "",
                "badResponse", badResponse != null ? badResponse : ""));
    }

    private String deriveGoal(IntentAnalysisResult intent) {
        if (intent.getReasoningSuggestion() != null && !intent.getReasoningSuggestion().isBlank()) {
            return intent.getReasoningSuggestion();
        }
        if (intent.hasIntents()) {
            return "Fulfill intent: " + intent.primaryIntent().getName();
        }
        return "Process user request";
    }
}
