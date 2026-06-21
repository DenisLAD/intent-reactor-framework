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
import com.intentreactor.strategies.config.StrategiesProperties;
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
 * STORM (Synthesis of Topic Outlines through Retrieval and Multi-perspective) planner:
 * generates diverse expert personas, each researches the topic from their viewpoint,
 * then synthesizes all research into a structured response.
 * <p>
 * Phases:
 * PERSPECTIVES - generate expert personas
 * RESEARCH     - each persona researches (tool calls, round-robin)
 * SYNTHESIZE   - combine all notes into final answer
 * <p>
 * Stored in session.attributes:
 * storm_phase          : PERSPECTIVES | RESEARCH | SYNTHESIZE
 * storm_perspectives   : List<StormPerspective>
 * storm_persona_index  : int
 * storm_research_steps : int
 * <p>
 * Activate with: intent-reactor.planning.strategy: storm
 */
public class StormPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(StormPlanner.class);

    private static final String PHASE_KEY = "storm_phase";
    private static final String PERSPECTIVES_KEY = "storm_perspectives";
    private static final String PERSONA_INDEX_KEY = "storm_persona_index";
    private static final String RESEARCH_STEPS_KEY = "storm_research_steps";

    private static final String PERSPECTIVES_SYSTEM =
            "Ты эксперт по многопerspectival-анализу. Для заданной темы сгенерируй {count} " +
                    "различных экспертных персон, которые исследуют её с разных точек зрения.\n\n" +
                    "Верни JSON-массив:\n" +
                    "[\n" +
                    "  {\"name\": \"Технический аналитик\", \"viewpoint\": \"Исследует технические аспекты и реализацию\"},\n" +
                    "  {\"name\": \"Бизнес-стратег\", \"viewpoint\": \"Анализирует деловые возможности и риски\"}\n" +
                    "]";

    private static final String RESEARCH_SYSTEM =
            "Ты {persona_name} — {persona_viewpoint}.\n\n" +
                    "Исследуй тему: {goal}\n\n" +
                    "Используй доступные инструменты для поиска информации. " +
                    "Верни JSON с инструментом для запроса:\n" +
                    "{\n" +
                    "  \"toolName\": \"knowledge_search\",\n" +
                    "  \"parameters\": {\"query\": \"...конкретный запрос с твоей точки зрения...\"},\n" +
                    "  \"rationale\": \"Почему именно этот запрос\"\n" +
                    "}";

    private static final String SYNTHESIZE_SYSTEM =
            "Ты синтезатор знаний. Объедини исследования всех экспертов в структурированный, " +
                    "исчерпывающий ответ на вопрос. Организуй информацию логично, устрани дублирование, " +
                    "выдели ключевые инсайты. Результат должен быть полным и хорошо структурированным.";

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int perspectiveCount;
    private final int maxResearchSteps;

    public StormPlanner(ChatClient chatClient, ToolProvider toolProvider,
                        ObjectMapper objectMapper, StrategiesProperties props) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.perspectiveCount = props.getStorm().getPerspectiveCount();
        this.maxResearchSteps = props.getStorm().getMaxResearchSteps();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "PERSPECTIVES");
        String goal = getGoal(session, intent);

        return switch (phase) {
            case "PERSPECTIVES" -> generatePerspectives(session, goal);
            case "RESEARCH" -> researchNext(session, goal);
            case "SYNTHESIZE" -> synthesize(session, goal);
            default -> generatePerspectives(session, goal);
        };
    }

    private Plan generatePerspectives(SessionState session, String goal) {
        String systemPrompt = PERSPECTIVES_SYSTEM.replace("{count}", String.valueOf(perspectiveCount));
        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Тема: " + goal)
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
        List<StormPerspective> perspectives =
                loadPerspectives(session);
        int personaIndex = (int) session.getAttributes().getOrDefault(PERSONA_INDEX_KEY, 0);
        int researchSteps = (int) session.getAttributes().getOrDefault(RESEARCH_STEPS_KEY, 0);

        // Capture last OBSERVE result into current persona's notes
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

        String systemPrompt = RESEARCH_SYSTEM
                .replace("{persona_name}", persona.getName())
                .replace("{persona_viewpoint}", persona.getViewpoint())
                .replace("{goal}", goal);

        try {
            List<Tool> tools = toolProvider.getAvailableTools(session);
            String toolsList = tools.stream()
                    .map(t -> t.getName() + ": " + t.getDescription())
                    .collect(Collectors.joining("\n"));

            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt + "\n\nДоступные инструменты:\n" + toolsList),
                    new UserMessage("Проведи исследование от лица своей роли.")
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
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(SYNTHESIZE_SYSTEM),
                    new UserMessage("Тема: " + goal + "\n\nИсследования экспертов:\n" + allNotes)
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

    private String getGoal(SessionState session, IntentAnalysisResult intent) {
        if (session.getPlanState() != null) {
            String g = session.getPlanState().getGoalDescription();
            if (g != null && !g.isBlank()) return g;
        }
        if (intent != null && intent.getReasoningSuggestion() != null
                && !intent.getReasoningSuggestion().isBlank()) {
            return intent.getReasoningSuggestion();
        }
        return "unknown";
    }

    private static class ResearchAction {
        String toolName;
        Map<String, Object> parameters;
        String rationale;
    }
}
