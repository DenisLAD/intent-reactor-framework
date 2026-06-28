package com.intentreactor.strategies.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * KnowAgent decorator: builds an Action Knowledge Base (preconditions/postconditions/contraindications)
 * from tool metadata, filters invalid tools based on current session state, and injects KB context
 * as a system message before delegating to the base ReACT planner.
 * <p>
 * Prompt file configured via intent-reactor.planning.strategies.prompts.knowagent-enrich
 * <p>
 * Activate with: intent-reactor.planning.strategy: knowagent
 */
public class KnowAgentPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(KnowAgentPlanner.class);

    private static final String KB_KEY          = StrategySessionKeys.KNOWAGENT_KB;
    private static final String INITIALIZED_KEY = StrategySessionKeys.KNOWAGENT_INITIALIZED;

    private static final Pattern PRECONDITION_PATTERN =
            Pattern.compile("(?i)(requires?|needs?|must first|after calling|before|depends on)");
    private static final Pattern POSTCONDITION_PATTERN =
            Pattern.compile("(?i)(sets?|stores?|creates?|saves?|returns?|provides?|establishes?)");
    private static final Pattern CONTRAINDICATION_PATTERN =
            Pattern.compile("(?i)(do not|don'?t|never|only if|avoid|not recommended)");

    private final Planner delegate;
    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final boolean enrichKnowledge;
    private final boolean filterByPreconditions;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String enrichPromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public KnowAgentPlanner(Planner delegate, ChatClient chatClient, ToolProvider toolProvider,
                            ObjectMapper objectMapper, StrategiesProperties props) {
        this.delegate = delegate;
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.enrichKnowledge = props.getKnowAgent().isEnrichKnowledge();
        this.filterByPreconditions = props.getKnowAgent().isFilterByPreconditions();
        this.enrichPromptPath = props.getPrompts().getKnowagentEnrich();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        ensureKbInitialized(session);
        injectKbContext(session);
        return delegate.plan(session, intent);
    }

    @SuppressWarnings("unchecked")
    private void ensureKbInitialized(SessionState session) {
        if (Boolean.TRUE.equals(session.getAttributes().get(INITIALIZED_KEY))) return;

        List<Tool> tools = toolProvider.getAvailableTools(session);
        Map<String, ToolKnowledge> kb = new LinkedHashMap<>();
        for (Tool tool : tools) {
            kb.put(tool.getName(), buildHeuristicKnowledge(tool));
        }

        if (enrichKnowledge && !tools.isEmpty()) {
            try {
                String enrichSystem = promptLoader.load(enrichPromptPath, Map.of());
                String toolList = buildToolListForEnrichment(tools);
                String response = chatClient.prompt(new Prompt(List.of(
                        new SystemMessage(enrichSystem),
                        new UserMessage(labels.getEnrichUserPrefix() + toolList)
                ))).call().content();
                mergeEnrichedKnowledge(kb, response);
                log.debug("[KnowAgent] LLM-enriched KB for session {}", session.getId());
            } catch (Exception e) {
                log.warn("[KnowAgent] LLM enrichment failed, using heuristic KB: {}", e.getMessage());
            }
        }

        session.getAttributes().put(KB_KEY, kb);
        session.getAttributes().put(INITIALIZED_KEY, true);
        log.debug("[KnowAgent] KB initialized with {} tools for session {}", kb.size(), session.getId());
    }

    private ToolKnowledge buildHeuristicKnowledge(Tool tool) {
        ToolKnowledge knowledge = new ToolKnowledge(tool.getName());
        String desc = tool.getDescription() != null ? tool.getDescription() : "";

        for (String sentence : desc.split("[.!?]")) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;
            if (PRECONDITION_PATTERN.matcher(sentence).find()) {
                knowledge.getPreconditions().add(sentence);
            }
            if (POSTCONDITION_PATTERN.matcher(sentence).find()) {
                knowledge.getPostconditions().add(sentence);
            }
            if (CONTRAINDICATION_PATTERN.matcher(sentence).find()) {
                knowledge.getContraindications().add(sentence);
            }
        }
        return knowledge;
    }

    private String buildToolListForEnrichment(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void mergeEnrichedKnowledge(Map<String, ToolKnowledge> kb, String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) return;
            cleaned = cleaned.substring(start, end + 1);

            Map<String, Map<String, Object>> enriched = objectMapper.readValue(cleaned,
                    new TypeReference<>() {
                    });

            for (Map.Entry<String, Map<String, Object>> entry : enriched.entrySet()) {
                ToolKnowledge knowledge = kb.computeIfAbsent(entry.getKey(), ToolKnowledge::new);
                Map<String, Object> data = entry.getValue();
                knowledge.getPreconditions().addAll(toStringList(data.get("preconditions")));
                knowledge.getPostconditions().addAll(toStringList(data.get("postconditions")));
                knowledge.getContraindications().addAll(toStringList(data.get("contraindications")));
            }
        } catch (Exception e) {
            log.warn("[KnowAgent] Failed to parse enriched KB response: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (value instanceof List) {
            return (List<String>) value;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private void injectKbContext(SessionState session) {
        Map<String, ToolKnowledge> kb = (Map<String, ToolKnowledge>)
                session.getAttributes().getOrDefault(KB_KEY, Map.of());
        if (kb.isEmpty()) return;

        Set<String> satisfiedPostconditions = computeSatisfiedPostconditions(session, kb);

        StringBuilder sb = new StringBuilder("[ACTION KNOWLEDGE BASE]\n");

        if (filterByPreconditions) {
            List<String> valid = new ArrayList<>();
            List<String> blocked = new ArrayList<>();

            for (ToolKnowledge knowledge : kb.values()) {
                boolean allMet = knowledge.getPreconditions().stream()
                        .allMatch(pre -> isMet(pre, satisfiedPostconditions, session));
                if (allMet) {
                    valid.add(knowledge.getToolName());
                } else {
                    String missing = knowledge.getPreconditions().stream()
                            .filter(pre -> !isMet(pre, satisfiedPostconditions, session))
                            .findFirst().orElse("unknown precondition");
                    blocked.add(knowledge.getToolName() + labels.getKbBlocked() + missing + "]");
                }
            }

            if (!valid.isEmpty()) {
                sb.append(labels.getKbAvailable()).append(String.join(", ", valid)).append("\n");
            }
            if (!blocked.isEmpty()) {
                sb.append(labels.getKbUnavailable()).append(String.join("; ", blocked)).append("\n");
            }
        }

        List<String> warnings = new ArrayList<>();
        for (ToolKnowledge knowledge : kb.values()) {
            for (String contra : knowledge.getContraindications()) {
                warnings.add(knowledge.getToolName() + ": " + labels.getKbWarning() + contra);
            }
        }
        if (!warnings.isEmpty()) {
            sb.append(labels.getKbContraindications());
            warnings.forEach(w -> sb.append("  - ").append(w).append("\n"));
        }

        session.addMessage(Message.system(sb.toString()));
    }

    private Set<String> computeSatisfiedPostconditions(SessionState session,
                                                       Map<String, ToolKnowledge> kb) {
        Set<String> satisfied = new HashSet<>();
        for (Message msg : session.getMessages()) {
            if (msg.getRole() == Message.Role.SYSTEM && msg.getContent() != null
                    && msg.getContent().startsWith("[TOOL_RESULT]")) {
                for (ToolKnowledge knowledge : kb.values()) {
                    if (msg.getContent().contains(knowledge.getToolName())) {
                        satisfied.addAll(knowledge.getPostconditions());
                        satisfied.add(knowledge.getToolName());
                        break;
                    }
                }
            }
        }
        return satisfied;
    }

    private boolean isMet(String precondition, Set<String> satisfied, SessionState session) {
        if (precondition == null || precondition.isBlank()) return true;
        for (String s : satisfied) {
            if (precondition.toLowerCase().contains(s.toLowerCase())) return true;
        }
        String lowerPre = precondition.toLowerCase();
        for (Message msg : session.getMessages()) {
            if (msg.getRole() == Message.Role.SYSTEM && msg.getContent() != null
                    && msg.getContent().startsWith("[TOOL_RESULT]")) {
                String content = msg.getContent().toLowerCase();
                String[] words = lowerPre.split("\\s+");
                for (String word : words) {
                    if (word.length() > 4 && content.contains(word)) return true;
                }
            }
        }
        return false;
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
}
