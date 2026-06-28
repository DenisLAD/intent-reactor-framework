package com.intentreactor.strategies.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * STORM planner: generates diverse expert personas, each researches the topic from their viewpoint,
 * then synthesizes all research into a structured response.
 * <p>
 * Prompt files configured via intent-reactor.planning.strategies.prompts.*
 * <p>
 * Activate with: intent-reactor.planning.strategy: storm
 */
public class StormPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(StormPlanner.class);

    private static final String PHASE_KEY          = StrategySessionKeys.STORM_PHASE;
    private static final String PERSPECTIVES_KEY   = StrategySessionKeys.STORM_PERSPECTIVES;
    private static final String PERSONA_INDEX_KEY  = StrategySessionKeys.STORM_PERSONA_INDEX;
    private static final String RESEARCH_STEPS_KEY = StrategySessionKeys.STORM_RESEARCH_STEPS;

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int perspectiveCount;
    private final int maxResearchSteps;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String perspectivesPromptPath;
    private final String researchPromptPath;
    private final String synthesizePromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public StormPlanner(ChatClient chatClient, ToolProvider toolProvider,
                        ObjectMapper objectMapper, StrategiesProperties props) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.perspectiveCount = props.getStorm().getPerspectiveCount();
        this.maxResearchSteps = props.getStorm().getMaxResearchSteps();
        this.perspectivesPromptPath = props.getPrompts().getStormPerspectives();
        this.researchPromptPath = props.getPrompts().getStormResearch();
        this.synthesizePromptPath = props.getPrompts().getStormSynthesize();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "PERSPECTIVES");
        String goal = getGoal(session);

        return switch (phase) {
            case "PERSPECTIVES" -> generatePerspectives(session, goal);
            case "RESEARCH" -> researchNext(session, goal);
            case "SYNTHESIZE" -> synthesize(session, goal);
            default -> generatePerspectives(session, goal);
        };
    }

    private Plan generatePerspectives(SessionState session, String goal) {
        try {
            String systemPrompt = promptLoader.load(perspectivesPromptPath,
                    Map.of("count", perspectiveCount));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(labels.getTopic() + goal)
            ))).call().content();

            List<StormPerspective> perspectives = parsePerspectives(response);
            if (perspectives.size() > perspectiveCount) perspectives = perspectives.subList(0, perspectiveCount);
            if (perspectives.isEmpty()) {
                return new SimplePlan(List.of(SimplePlanStep.fail("Failed to generate perspectives")));
            }

            session.getAttributes().put(PERSPECTIVES_KEY, perspectives);
            session.getAttributes().put(PERSONA_INDEX_KEY, 0);
            session.getAttributes().put(RESEARCH_STEPS_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "RESEARCH");

            log.debug("[STORM] Generated {} perspectives for session {}", perspectives.size(), session.getId());
            return researchNext(session, goal);

        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.fail("Perspective generation failed: " + e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private Plan researchNext(SessionState session, String goal) {
        List<StormPerspective> perspectives = loadPerspectives(session);
        int personaIndex = (int) session.getAttributes().getOrDefault(PERSONA_INDEX_KEY, 0);
        int researchSteps = (int) session.getAttributes().getOrDefault(RESEARCH_STEPS_KEY, 0);

        if (researchSteps > 0 && personaIndex > 0) {
            List<Message> messages = session.getMessages();
            if (!messages.isEmpty()) {
                Message last = messages.get(messages.size() - 1);
                if (last.getRole() == Message.Role.SYSTEM) {
                    int prevIndex = (personaIndex - 1) % perspectives.size();
                    perspectives.get(prevIndex).getNotes().add(last.getContent());
                    savePerspectives(session, perspectives);
                }
            }
        }

        if (researchSteps >= maxResearchSteps || personaIndex >= perspectives.size() * 2) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }

        StormPerspective persona = perspectives.get(personaIndex % perspectives.size());

        try {
            List<Tool> tools = toolProvider.getAvailableTools(session);
            String toolsList = tools.stream()
                    .map(t -> t.getName() + ": " + t.getDescription())
                    .collect(Collectors.joining("\n"));

            String systemPrompt = promptLoader.load(researchPromptPath, Map.of(
                    "persona_name", persona.getName(),
                    "persona_viewpoint", persona.getViewpoint(),
                    "tools", toolsList
            ));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(labels.getTopic() + goal + "\n\n" + labels.getConductResearch())
            ))).call().content();

            ResearchAction ra = parseResearchAction(response);
            session.getAttributes().put(PERSONA_INDEX_KEY, personaIndex + 1);
            session.getAttributes().put(RESEARCH_STEPS_KEY, researchSteps + 1);

            log.debug("[STORM] Persona '{}' researching, step {}/{} for session {}",
                    persona.getName(), researchSteps + 1, maxResearchSteps, session.getId());

            boolean toolExists = tools.stream().anyMatch(t -> t.getName().equals(ra.toolName));
            if (!toolExists || ra.toolName == null) {
                return researchNext(session, goal);
            }

            Action action = new SimpleAction(ra.toolName, ra.parameters);
            return new SimplePlan(List.of(SimplePlanStep.act(action,
                    "[" + persona.getName() + "] " + ra.rationale, false)));

        } catch (Exception e) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }
    }

    @SuppressWarnings("unchecked")
    private Plan synthesize(SessionState session, String goal) {
        List<StormPerspective> perspectives = loadPerspectives(session);

        StringBuilder allNotes = new StringBuilder();
        perspectives.forEach(p -> {
            allNotes.append("\n## ").append(p.getName()).append(" (").append(p.getViewpoint()).append(")\n");
            p.getNotes().forEach(note -> allNotes.append("- ").append(note).append("\n"));
        });

        try {
            String system = promptLoader.load(synthesizePromptPath, Map.of());
            String userMsg = labels.getTopic() + goal + labels.getExpertResearch() + allNotes;

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(userMsg)
            ))).call().content();

            return new SimplePlan(List.of(SimplePlanStep.done(response)));
        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done(allNotes.toString())));
        }
    }

    @SuppressWarnings("unchecked")
    private List<StormPerspective> loadPerspectives(SessionState session) {
        Object raw = session.getAttributes().get(PERSPECTIVES_KEY);
        if (raw == null) return new ArrayList<>();
        try {
            return objectMapper.convertValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void savePerspectives(SessionState session, List<StormPerspective> perspectives) {
        session.getAttributes().put(PERSPECTIVES_KEY, perspectives);
    }

    private List<StormPerspective> parsePerspectives(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            return objectMapper.readValue(cleaned, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private ResearchAction parseResearchAction(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
            ResearchAction ra = new ResearchAction();
            ra.toolName = (String) map.get("toolName");
            ra.parameters = (Map<String, Object>) map.getOrDefault("parameters", Map.of());
            ra.rationale = (String) map.getOrDefault("rationale", "");
            return ra;
        } catch (Exception e) {
            ResearchAction ra = new ResearchAction();
            ra.toolName = null;
            return ra;
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

    private static class ResearchAction {
        String toolName;
        Map<String, Object> parameters;
        String rationale;
    }
}
