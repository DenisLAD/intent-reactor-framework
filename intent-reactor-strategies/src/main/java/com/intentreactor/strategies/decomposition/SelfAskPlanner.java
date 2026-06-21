package com.intentreactor.strategies.decomposition;

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
import com.intentreactor.api.StepType;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-Ask planner: decomposes the goal into sub-questions, answers each one (using tools where
 * needed), then synthesizes all answers into the final response.
 * <p>
 * Phases stored in session.attributes:
 * sa_phase       : DECOMPOSE | ANSWER | SYNTHESIZE
 * sa_questions   : List<Map> [{question, requires_tool}]
 * sa_answers     : Map<Integer, String>
 * sa_q_index     : int (current question index)
 * <p>
 * Activate with: intent-reactor.planning.strategy: self-ask
 */
public class SelfAskPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(SelfAskPlanner.class);

    private static final String PHASE_KEY = "sa_phase";
    private static final String QUESTIONS_KEY = "sa_questions";
    private static final String ANSWERS_KEY = "sa_answers";
    private static final String INDEX_KEY = "sa_q_index";

    private static final String DECOMPOSE_SYSTEM =
            "Ты эксперт по декомпозиции задач. Проанализируй запрос и определи, " +
                    "нужны ли вспомогательные вопросы для его решения. " +
                    "Верни JSON-массив вспомогательных вопросов. " +
                    "Если вопрос прост и не требует декомпозиции — верни пустой массив [].\n\n" +
                    "Формат ответа:\n" +
                    "[\n" +
                    "  {\"question\": \"...\", \"requires_tool\": true}\n" +
                    "]\n\n" +
                    "requires_tool: true — если для ответа нужен инструмент (погода, время, данные); " +
                    "false — если можно ответить рассуждением.";

    private static final String SYNTHESIZE_SYSTEM =
            "Ты помощник. Используй собранные ответы на вспомогательные вопросы, " +
                    "чтобы дать исчерпывающий финальный ответ на исходный вопрос. " +
                    "Синтезируй информацию в чёткий, структурированный ответ.";

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int maxSubQuestions;

    public SelfAskPlanner(ChatClient chatClient, ToolProvider toolProvider,
                          ObjectMapper objectMapper, StrategiesProperties props) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.maxSubQuestions = props.getSelfAsk().getMaxSubQuestions();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "DECOMPOSE");
        String goal = getGoal(session, intent);

        return switch (phase) {
            case "DECOMPOSE" -> decompose(session, goal);
            case "ANSWER" -> answerNext(session, intent, goal);
            case "SYNTHESIZE" -> synthesize(session, goal);
            default -> decompose(session, goal);
        };
    }

    @SuppressWarnings("unchecked")
    private Plan decompose(SessionState session, String goal) {
        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(DECOMPOSE_SYSTEM),
                    new UserMessage("Задача: " + goal)
            ))).call().content();

            List<Map<String, Object>> questions = parseQuestions(response);
            if (questions.size() > maxSubQuestions) {
                questions = questions.subList(0, maxSubQuestions);
            }

            if (questions.isEmpty()) {
                log.debug("[Self-Ask] No sub-questions needed for session {}, proceeding directly", session.getId());
                session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
                session.getAttributes().put(ANSWERS_KEY, new HashMap<>());
                return synthesize(session, goal);
            }

            session.getAttributes().put(QUESTIONS_KEY, questions);
            session.getAttributes().put(ANSWERS_KEY, new LinkedHashMap<>());
            session.getAttributes().put(INDEX_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "ANSWER");

            log.debug("[Self-Ask] Decomposed into {} sub-questions for session {}", questions.size(), session.getId());
            return answerNext(session, null, goal);

        } catch (Exception e) {
            log.warn("[Self-Ask] Decomposition failed: {}", e.getMessage());
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }
    }

    @SuppressWarnings("unchecked")
    private Plan answerNext(SessionState session, IntentAnalysisResult intent, String goal) {
        List<Map<String, Object>> questions =
                (List<Map<String, Object>>) session.getAttributes().get(QUESTIONS_KEY);
        Map<String, String> answers =
                (Map<String, String>) session.getAttributes().get(ANSWERS_KEY);
        int index = (int) session.getAttributes().getOrDefault(INDEX_KEY, 0);

        // Check if last OBSERVE added an answer
        if (intent != null && index > 0) {
            List<Message> messages = session.getMessages();
            if (!messages.isEmpty()) {
                Message last = messages.get(messages.size() - 1);
                if (last.getRole() == Message.Role.SYSTEM || last.getRole() == Message.Role.ASSISTANT) {
                    String qKey = "q" + (index - 1);
                    answers.put(qKey, last.getContent());
                    session.getAttributes().put(ANSWERS_KEY, answers);
                }
            }
        }

        if (index >= questions.size()) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }

        Map<String, Object> q = questions.get(index);
        String question = (String) q.get("question");
        boolean requiresTool = Boolean.TRUE.equals(q.get("requires_tool"));

        session.getAttributes().put(INDEX_KEY, index + 1);
        session.addMessage(Message.system("[SUB-QUESTION " + (index + 1) + "] " + question));

        if (requiresTool) {
            List<Tool> tools = toolProvider.getAvailableTools(session);
            if (!tools.isEmpty()) {
                Tool firstTool = tools.get(0);
                Action action = new SimpleAction(firstTool.getName(), Map.of("query", question));
                return new SimplePlan(List.of(SimplePlanStep.act(action, "Answer sub-question: " + question, false)));
            }
        }

        // Answer by reasoning
        return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null, "Sub-question: " + question, false)));
    }

    @SuppressWarnings("unchecked")
    private Plan synthesize(SessionState session, String goal) {
        Map<String, String> answers =
                (Map<String, String>) session.getAttributes().getOrDefault(ANSWERS_KEY, Map.of());

        StringBuilder context = new StringBuilder();
        answers.forEach((k, v) -> context.append(k).append(": ").append(v).append("\n"));

        try {
            String userMsg = "Исходный вопрос: " + goal + "\n\nСобранные ответы:\n" + context +
                    "\n\nДай финальный ответ на исходный вопрос.";

            String finalAnswer = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(SYNTHESIZE_SYSTEM),
                    new UserMessage(userMsg)
            ))).call().content();

            return new SimplePlan(List.of(SimplePlanStep.done(finalAnswer)));

        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done(
                    "Собранные ответы: " + context)));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseQuestions(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            // Find the JSON array
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            return objectMapper.readValue(cleaned, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
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
}
