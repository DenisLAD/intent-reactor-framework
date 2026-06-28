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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-Ask planner: decomposes the goal into sub-questions, answers each one (using tools where
 * needed), then synthesizes all answers into the final response.
 * <p>
 * Prompt files configured via intent-reactor.planning.strategies.prompts.*
 * <p>
 * Activate with: intent-reactor.planning.strategy: self-ask
 */
public class SelfAskPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(SelfAskPlanner.class);

    private static final String PHASE_KEY     = StrategySessionKeys.SA_PHASE;
    private static final String QUESTIONS_KEY = StrategySessionKeys.SA_QUESTIONS;
    private static final String ANSWERS_KEY   = StrategySessionKeys.SA_ANSWERS;
    private static final String INDEX_KEY     = StrategySessionKeys.SA_INDEX;

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int maxSubQuestions;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String decomposePromptPath;
    private final String synthesizePromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public SelfAskPlanner(ChatClient chatClient, ToolProvider toolProvider,
                          ObjectMapper objectMapper, StrategiesProperties props) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.maxSubQuestions = props.getSelfAsk().getMaxSubQuestions();
        this.decomposePromptPath = props.getPrompts().getSelfAskDecompose();
        this.synthesizePromptPath = props.getPrompts().getSelfAskSynthesize();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "DECOMPOSE");
        String goal = getGoal(session);

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
            String system = promptLoader.load(decomposePromptPath,
                    Map.of("max_questions", maxSubQuestions));
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(labels.getTask() + goal)
            ))).call().content();

            List<Map<String, Object>> questions = parseQuestions(response);
            if (questions.size() > maxSubQuestions) {
                questions = questions.subList(0, maxSubQuestions);
            }

            if (questions.isEmpty()) {
                log.info("[Self-Ask] No sub-questions needed for session {}, answering directly", session.getId());
                session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
                session.getAttributes().put(ANSWERS_KEY, new HashMap<>());
                // Return a REASON step so the service publishes PlanStepStartedEvent → SSE → UI shows progress.
                // Next call to plan() will find phase="SYNTHESIZE" → synthesize() → DONE.
                return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null,
                        labels.getDirectAnswerReason() + goal, false)));
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

        // Collect tool result from the previous ACT step — only for tool-based questions
        if (intent != null && index > 0) {
            Map<String, Object> prevQ = questions.get(index - 1);
            if (Boolean.TRUE.equals(prevQ.get("requires_tool"))) {
                List<Message> messages = session.getMessages();
                if (!messages.isEmpty()) {
                    Message last = messages.get(messages.size() - 1);
                    if (last.getRole() == Message.Role.SYSTEM || last.getRole() == Message.Role.ASSISTANT) {
                        answers.put("q" + (index - 1), last.getContent());
                        session.getAttributes().put(ANSWERS_KEY, answers);
                    }
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

        int nextIndex = index + 1;
        session.getAttributes().put(INDEX_KEY, nextIndex);

        if (requiresTool) {
            List<Tool> tools = toolProvider.getAvailableTools(session);
            if (!tools.isEmpty()) {
                session.addMessage(Message.system(labels.getSubQuestion() + (index + 1) + "] " + question));
                Tool firstTool = tools.get(0);
                Action action = new SimpleAction(firstTool.getName(), Map.of("query", question));
                return new SimplePlan(List.of(SimplePlanStep.act(action, "Answer sub-question: " + question, false)));
            }
        }

        // Answer via LLM inline — no tool needed (or no tool available)
        try {
            String answer = chatClient.prompt(new Prompt(List.of(
                    new UserMessage(question)
            ))).call().content();
            answers.put("q" + index, answer);
            session.getAttributes().put(ANSWERS_KEY, answers);
            log.debug("[Self-Ask] Answered sub-question {} via LLM for session {}", index, session.getId());
        } catch (Exception e) {
            log.warn("[Self-Ask] LLM answer failed for sub-question {}: {}", index, e.getMessage());
        }

        // Answer is already stored in answers["qN"]. Return a REASON step so the service
        // publishes PlanStepStartedEvent → SSE → progress visible in UI.
        // Next call to plan() will advance to the next sub-question or synthesize.
        return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null,
                labels.getSubQuestion() + (index + 1) + "] " + question, false)));
    }

    @SuppressWarnings("unchecked")
    private Plan synthesize(SessionState session, String goal) {
        Map<String, String> answers =
                (Map<String, String>) session.getAttributes().getOrDefault(ANSWERS_KEY, Map.of());

        try {
            String synthesizeSystem = promptLoader.load(synthesizePromptPath, Map.of());
            String userMsg;
            if (answers.isEmpty()) {
                userMsg = labels.getOriginalQuestion() + goal + labels.getAnswerDirectly();
            } else {
                StringBuilder context = new StringBuilder();
                answers.forEach((k, v) -> context.append(k).append(": ").append(v).append("\n"));
                userMsg = labels.getOriginalQuestion() + goal +
                        labels.getCollectedAnswers() + context +
                        labels.getGiveFinalAnswer();
            }

            String finalAnswer = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(synthesizeSystem),
                    new UserMessage(userMsg)
            ))).call().content();

            return new SimplePlan(List.of(SimplePlanStep.done(finalAnswer)));

        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done(
                    answers.isEmpty() ? goal : String.valueOf(answers))));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseQuestions(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
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
}
