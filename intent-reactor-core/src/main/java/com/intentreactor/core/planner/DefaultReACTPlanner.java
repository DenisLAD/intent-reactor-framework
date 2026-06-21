package com.intentreactor.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
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
import com.intentreactor.core.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultReACTPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(DefaultReACTPlanner.class);

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    final IntentReactorProperties properties;
    private final ObjectMapper objectMapper;
    final PromptLoader promptLoader = new PromptLoader();
    private final List<PromptContextProvider> promptContextProviders;
    private final MessageCompressor messageCompressor;

    public DefaultReACTPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper) {
        this(chatClient, toolProvider, properties, objectMapper, List.of(), null);
    }

    public DefaultReACTPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper,
                               List<PromptContextProvider> promptContextProviders) {
        this(chatClient, toolProvider, properties, objectMapper, promptContextProviders, null);
    }

    public DefaultReACTPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper,
                               List<PromptContextProvider> promptContextProviders,
                               MessageCompressor messageCompressor) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.promptContextProviders = promptContextProviders != null ? promptContextProviders : List.of();
        this.messageCompressor = messageCompressor;
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
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            try {
                sb.append("  Parameters: ").append(objectMapper.writeValueAsString(tool.getParameterSchema())).append("\n");
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    protected List<org.springframework.ai.chat.messages.Message> buildMessages(
            String systemPrompt, SessionState session) {

        IntentReactorProperties.ContextWindowConfig ctx = properties.getPlanning().getContextWindow();
        int maxMsgs = ctx.getMaxMessages();
        int maxChars = ctx.getMaxMessageChars();
        String suffix = ctx.getTruncationSuffix();

        List<Message> all = session.getMessages();
        List<Message> windowed;
        if (maxMsgs > 0 && all.size() > maxMsgs) {
            List<Message> tail = new ArrayList<>(all.subList(all.size() - maxMsgs, all.size()));

            // Collect pinned messages that were evicted from the tail (identity-based check).
            List<Message> missingPinned = new ArrayList<>();
            for (Message m : all) {
                if (!m.isPinned()) continue;
                boolean inTail = false;
                for (Message t : tail) { if (t == m) { inTail = true; break; } }
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
        } else {
            windowed = all;
        }

        // Keep only the latest take_snapshot message — older ones show stale DOM state
        // and consume LLM context without providing useful information.
        final String SNAPSHOT_PREFIX = "[TOOL_RESULT] take_snapshot:";
        boolean latestSnapshotSeen = false;
        List<Message> deduped = new ArrayList<>(windowed.size());
        int droppedSnapshots = 0;
        for (int i = windowed.size() - 1; i >= 0; i--) {
            Message m = windowed.get(i);
            boolean isSnapshot = m.getRole() == Message.Role.SYSTEM
                    && m.getContent() != null
                    && m.getContent().startsWith(SNAPSHOT_PREFIX);
            if (isSnapshot) {
                if (latestSnapshotSeen) {
                    droppedSnapshots++;
                    continue;
                }
                latestSnapshotSeen = true;
            }
            deduped.add(0, m);
        }
        if (droppedSnapshots > 0) {
            log.debug("[buildMessages] dropped {} stale snapshot(s)", droppedSnapshots);
            windowed = deduped;
        }

        int maxSnapshotChars = ctx.getMaxSnapshotChars();

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        for (Message m : windowed) {
            String content = m.getContent();
            boolean isSnapshot = m.getRole() == Message.Role.SYSTEM
                    && content != null
                    && content.startsWith(SNAPSHOT_PREFIX);
            int effectiveMaxChars = isSnapshot ? maxSnapshotChars : maxChars;
            if (effectiveMaxChars > 0 && content != null && content.length() > effectiveMaxChars) {
                content = content.substring(0, effectiveMaxChars) + suffix;
                log.debug("[buildMessages] truncated {} message: {} -> {} chars",
                        isSnapshot ? "snapshot" : "regular", m.getContent().length(), effectiveMaxChars);
            }
            switch (m.getRole()) {
                case USER -> messages.add(new UserMessage(content));
                case ASSISTANT -> messages.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
                // Tool results stored as SYSTEM in session must be sent as UserMessage for LLMs.
                case SYSTEM -> messages.add(new UserMessage(content));
            }
        }

        // Token-based compression: when estimated tokens exceed the trigger threshold,
        // summarize old messages (outside the sliding window) via LLM and inject the summary
        // after the system prompt. Skipped if messageCompressor is null (feature disabled).
        IntentReactorProperties.CompressionConfig comp = ctx.getCompression();
        if (comp.isEnabled() && messageCompressor != null) {
            int estimated = messageCompressor.estimateTokens(messages);
            int threshold = (int) (comp.getMaxTokens() * comp.getTriggerRatio());
            if (estimated > threshold) {
                // Exclude pinned messages from compression — they are re-inserted into the window verbatim.
                List<Message> oldMessages;
                if (maxMsgs > 0 && all.size() > maxMsgs) {
                    oldMessages = all.subList(0, all.size() - maxMsgs).stream()
                            .filter(m -> !m.isPinned())
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                } else {
                    oldMessages = List.of();
                }
                if (!oldMessages.isEmpty()) {
                    String summary = messageCompressor.compress(oldMessages, session);
                    if (!summary.isBlank()) {
                        messages.add(1, new UserMessage("[ИСТОРИЯ ДИАЛОГА]\n" + summary));
                        log.debug("[buildMessages] compression triggered: estimated={} tokens (threshold={}), compressed {} old messages",
                                estimated, threshold, oldMessages.size());
                    }
                }
            }
        }

        // Chat models (including LM Studio) require the conversation to end with a user message.
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

    @SuppressWarnings("unchecked")
    Plan parseResponse(String response, List<Tool> tools, SessionState session) throws Exception {
        String json = extractJson(response);
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

    private String extractJson(String response) {
        if (response == null) throw new IllegalArgumentException("Empty LLM response");
        String stripped = stripMarkdownFences(response);
        List<String> candidates = extractAllJsonCandidates(stripped);
        if (candidates.isEmpty()) {
            String repaired = repairTruncatedJson(stripped);
            if (repaired != null) candidates = extractAllJsonCandidates(repaired);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No JSON object found in response: " + response);
        }
        // Prefer the candidate that contains a known ReACT key (done / failed / toolName).
        // LLMs sometimes emit their own JSON first and the proper ReACT object later.
        for (String candidate : candidates) {
            try {
                Map<?, ?> m = objectMapper.readValue(candidate, Map.class);
                if (m.containsKey("done") || m.containsKey("failed") || m.containsKey("toolName")) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        // Fall back to the last candidate: LLMs tend to place the "conclusion" at the end.
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

    private String repairTruncatedJson(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') { i++; }
                else if (c == '"') { inString = false; }
            } else {
                if (c == '"') { inString = true; }
                else if (c == '{') { depth++; }
                else if (c == '}' && depth > 0) { depth--; }
            }
        }
        if (depth <= 0 || depth > 10) return null;
        StringBuilder sb = new StringBuilder(text.strip());
        if (inString) sb.append('"');
        for (int i = 0; i < depth; i++) sb.append('}');
        return sb.toString();
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
