package com.intentreactor.strategies.search;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReTreVal (Reasoning Tree with Validation): training-free strategy that builds a tree of
 * reasoning steps with dual validation (self-eval + critic scoring) per node,
 * typed-failure backtracking with error context injection, and session-scoped memory accumulation.
 * <p>
 * Session attributes:
 * retreval_tree         : RetrevalTree
 * retreval_memory       : List<RetrevalPattern>
 * retreval_phase        : String (INITIAL|EXPAND|VALIDATE|EXECUTE|BACKTRACK|SYNTHESIZE)
 * retreval_candidates   : List<RetrevalNode> (candidates being validated)
 * retreval_validate_idx : int (current candidate index in VALIDATE)
 * <p>
 * Activate with: intent-reactor.planning.strategy: retreval
 */
public class ReTreValPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(ReTreValPlanner.class);

    private static final String TREE_KEY = "retreval_tree";
    private static final String MEMORY_KEY = "retreval_memory";
    private static final String PHASE_KEY = "retreval_phase";
    private static final String CANDIDATES_KEY = "retreval_candidates";
    private static final String VALIDATE_IDX_KEY = "retreval_validate_idx";

    private static final double SHORT_CIRCUIT_THRESHOLD = 0.8;

    // ── EXPAND ───────────────────────────────────────────────────────────────────
    private static final String EXPAND_SYSTEM =
            "Ты — генератор шагов рассуждения. Для продвижения к цели сгенерируй {K} " +
                    "кандидатов следующего шага.\n\n" +
                    "Доступные инструменты:\n{tools}\n\n" +
                    "Накопленные паттерны (помни):\n{memory}\n\n" +
                    "Верни JSON-массив кандидатов:\n" +
                    "[{\"content\": \"...\", \"toolName\": \"имя_инструмента_или_null\", " +
                    "\"toolParameters\": {}, \"rationale\": \"...\"}]";

    // ── SELF-SCORE ────────────────────────────────────────────────────────────────
    private static final String SELF_SCORE_SYSTEM =
            "Оцени этот шаг рассуждения по шкале 0.0–1.0:\n" +
                    "- 0.0: полностью нерелевантен или ошибочен\n" +
                    "- 0.5: частично полезен\n" +
                    "- 1.0: точный и продвигающий к цели шаг\n\n" +
                    "Верни JSON: {\"score\": 0.0-1.0, \"rationale\": \"...\"}";

    // ── CRITIC-SCORE ──────────────────────────────────────────────────────────────
    private static final String CRITIC_SCORE_SYSTEM =
            "Ты — независимый критик. Оцени этот шаг рассуждения строго и беспристрастно.\n" +
                    "Ищи: логические ошибки, нерелевантность, повторение предыдущих шагов, " +
                    "завышенные утверждения.\n\n" +
                    "Верни JSON: {\"score\": 0.0-1.0, \"issues\": \"...\"}";

    // ── SYNTHESIZE ────────────────────────────────────────────────────────────────
    private static final String SYNTHESIZE_SYSTEM =
            "Ты — синтезатор. На основе проверенной цепочки рассуждений дай финальный, " +
                    "связный и исчерпывающий ответ на исходный запрос.";

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int maxTreeDepth;
    private final int candidatesPerStep;
    private final double validationThreshold;

    public ReTreValPlanner(ChatClient chatClient, ToolProvider toolProvider,
                           ObjectMapper objectMapper, StrategiesProperties props) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        StrategiesProperties.RetrevalConfig cfg = props.getRetreval();
        this.maxTreeDepth = cfg.getMaxTreeDepth();
        this.candidatesPerStep = cfg.getCandidatesPerStep();
        this.validationThreshold = cfg.getValidationThreshold();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "INITIAL");
        String goal = getGoal(session, intent);

        return switch (phase) {
            case "INITIAL" -> initialize(session, goal);
            case "EXPAND" -> expand(session, goal);
            case "VALIDATE" -> validate(session, goal);
            case "EXECUTE" -> execute(session, goal);
            case "BACKTRACK" -> backtrack(session, goal);
            case "SYNTHESIZE" -> synthesize(session, goal);
            default -> initialize(session, goal);
        };
    }

    // ── INITIAL ──────────────────────────────────────────────────────────────────

    private Plan initialize(SessionState session, String goal) {
        RetrevalTree tree = RetrevalTree.withRoot(goal);
        session.getAttributes().put(TREE_KEY, tree);
        session.getAttributes().put(MEMORY_KEY, new ArrayList<RetrevalPattern>());
        session.getAttributes().put(PHASE_KEY, "EXPAND");
        log.debug("[ReTreVal] Initialized tree for session {}", session.getId());
        return expand(session, goal);
    }

    // ── EXPAND ───────────────────────────────────────────────────────────────────

    private Plan expand(SessionState session, String goal) {
        RetrevalTree tree = loadTree(session);

        // Absorb observation from previous ACT into current node
        if (tree.getCurrent() != null && tree.getCurrent().getToolName() != null
                && tree.getCurrent().getToolObservation() == null) {
            List<Message> messages = session.getMessages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                if (msg.getRole() == Message.Role.SYSTEM && msg.getContent() != null) {
                    tree.getCurrent().setToolObservation(msg.getContent());
                    // Record success pattern
                    addPattern(session, new RetrevalPattern("SUCCESS",
                            tree.getCurrent().getContent(), null,
                            (tree.getCurrent().getSelfScore() + tree.getCurrent().getCriticScore()) / 2.0));
                    break;
                }
            }
            saveTree(session, tree);
        }

        // Check depth limit
        int depth = tree.getCurrent() != null ? tree.getCurrent().getDepth() : 0;
        if (depth >= maxTreeDepth) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }

        List<Tool> tools = toolProvider.getAvailableTools(session);
        String path = buildPath(tree);
        String memory = buildMemorySummary(session);

        String systemPrompt = EXPAND_SYSTEM
                .replace("{K}", String.valueOf(candidatesPerStep))
                .replace("{tools}", formatTools(tools))
                .replace("{memory}", memory);

        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Цель: " + goal + "\n\nТекущий путь:\n" + path)
            ))).call().content();

            List<RetrevalNode> candidates = parseCandidates(response, tree);
            if (candidates.isEmpty()) {
                session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
                return synthesize(session, goal);
            }

            session.getAttributes().put(CANDIDATES_KEY, candidates);
            session.getAttributes().put(VALIDATE_IDX_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "VALIDATE");

            log.debug("[ReTreVal] Expanded {} candidates at depth {} for session {}",
                    candidates.size(), depth, session.getId());
            return validate(session, goal);

        } catch (Exception e) {
            log.warn("[ReTreVal] Expand failed: {}", e.getMessage());
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }
    }

    // ── VALIDATE ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Plan validate(SessionState session, String goal) {
        List<RetrevalNode> candidates = (List<RetrevalNode>) session.getAttributes().get(CANDIDATES_KEY);
        if (candidates == null || candidates.isEmpty()) {
            session.getAttributes().put(PHASE_KEY, "BACKTRACK");
            return backtrack(session, goal);
        }

        int idx = (int) session.getAttributes().getOrDefault(VALIDATE_IDX_KEY, 0);
        if (idx >= candidates.size()) {
            session.getAttributes().put(PHASE_KEY, "BACKTRACK");
            return backtrack(session, goal);
        }

        RetrevalNode candidate = candidates.get(idx);
        RetrevalTree tree = loadTree(session);
        String path = buildPath(tree);

        double selfScore = scoreSelf(candidate, goal, path);
        double criticScore = scoreCritic(candidate, goal, path);
        double avg = (selfScore + criticScore) / 2.0;

        candidate.setSelfScore(selfScore);
        candidate.setCriticScore(criticScore);

        log.debug("[ReTreVal] Candidate {} scored {:.2f} (self={:.2f}, critic={:.2f}) in session {}",
                idx, avg, selfScore, criticScore, session.getId());

        if (avg >= validationThreshold) {
            // Accept this candidate
            String parentId = tree.getCurrentNodeId();
            candidate.setParentId(parentId);
            candidate.setDepth(tree.getCurrent() != null ? tree.getCurrent().getDepth() + 1 : 1);
            candidate.setState("VALIDATED");

            tree.getNodes().put(candidate.getId(), candidate);
            if (tree.getCurrent() != null) tree.getCurrent().getChildIds().add(candidate.getId());
            tree.setCurrentNodeId(candidate.getId());
            saveTree(session, tree);
            session.getAttributes().put(PHASE_KEY, "EXECUTE");

            return execute(session, goal);
        }

        // If short-circuit threshold reached (but we haven't passed), try next candidate
        session.getAttributes().put(VALIDATE_IDX_KEY, idx + 1);
        return validate(session, goal);
    }

    // ── EXECUTE ──────────────────────────────────────────────────────────────────

    private Plan execute(SessionState session, String goal) {
        RetrevalTree tree = loadTree(session);
        RetrevalNode node = tree.getCurrent();
        if (node == null) {
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }

        if (node.getToolName() != null && !node.getToolName().isBlank()) {
            // Return ACT — next plan() call returns to EXPAND and absorbs observation
            session.getAttributes().put(PHASE_KEY, "EXPAND");
            Action action = new SimpleAction(node.getToolName(),
                    node.getToolParameters() != null ? node.getToolParameters() : Map.of());
            return new SimplePlan(List.of(SimplePlanStep.act(action, node.getContent(), false)));
        }

        // Pure reasoning
        session.getAttributes().put(PHASE_KEY, "EXPAND");
        return new SimplePlan(List.of(SimplePlanStep.reason(node.getContent())));
    }

    // ── BACKTRACK ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Plan backtrack(SessionState session, String goal) {
        List<RetrevalNode> candidates = (List<RetrevalNode>) session.getAttributes().getOrDefault(
                CANDIDATES_KEY, List.of());
        RetrevalTree tree = loadTree(session);
        RetrevalNode current = tree.getCurrent();

        // Determine failure type
        String failureType = "SCORE_TOO_LOW";
        if (!candidates.isEmpty()) {
            String content = candidates.get(0).getContent();
            // Check for reasoning loop (content similar to existing node)
            boolean loop = tree.getNodes().values().stream()
                    .anyMatch(n -> n != current && n.getContent() != null
                            && n.getContent().equalsIgnoreCase(content));
            if (loop) failureType = "REASONING_LOOP";
        }

        double maxScore = candidates.stream()
                .mapToDouble(c -> (c.getSelfScore() + c.getCriticScore()) / 2.0)
                .max().orElse(0.0);

        // Record failure pattern
        String failedContent = candidates.isEmpty() ? "unknown" : candidates.get(0).getContent();
        addPattern(session, new RetrevalPattern("FAILURE", failedContent, failureType, maxScore));

        // Mark current node as failed
        if (current != null) {
            current.setState("FAILED");
            current.setFailureType(failureType);
            current.setFailureContext("Все " + candidates.size() + " кандидатов провалены. Тип: " + failureType);
        }

        // Move to parent
        String parentId = current != null ? current.getParentId() : null;
        if (parentId == null || !tree.getNodes().containsKey(parentId)) {
            saveTree(session, tree);
            session.getAttributes().put(PHASE_KEY, "SYNTHESIZE");
            return synthesize(session, goal);
        }

        tree.setCurrentNodeId(parentId);
        saveTree(session, tree);
        session.getAttributes().put(CANDIDATES_KEY, new ArrayList<>());
        session.getAttributes().put(VALIDATE_IDX_KEY, 0);
        session.getAttributes().put(PHASE_KEY, "EXPAND");

        log.debug("[ReTreVal] Backtracked to parent ({}) in session {}", parentId, session.getId());
        return new SimplePlan(List.of(SimplePlanStep.reason(
                "Отступаю назад. Причина: " + failureType + ". Попробую другой подход.")));
    }

    // ── SYNTHESIZE ────────────────────────────────────────────────────────────────

    private Plan synthesize(SessionState session, String goal) {
        RetrevalTree tree = loadTree(session);
        List<RetrevalNode> path = tree.getValidatedPath();

        StringBuilder pathText = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            RetrevalNode node = path.get(i);
            pathText.append(i).append(". ").append(node.getContent());
            if (node.getToolObservation() != null) {
                pathText.append("\n   → ").append(truncate(node.getToolObservation(), 300));
            }
            pathText.append("\n");
        }

        try {
            String finalAnswer = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(SYNTHESIZE_SYSTEM),
                    new UserMessage("Цель: " + goal + "\n\nЦепочка рассуждений:\n" + pathText)
            ))).call().content();
            return new SimplePlan(List.of(SimplePlanStep.done(finalAnswer)));
        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done("Итог: " + pathText)));
        }
    }

    // ── Scoring ───────────────────────────────────────────────────────────────────

    private double scoreSelf(RetrevalNode candidate, String goal, String path) {
        try {
            String userMsg = "Цель: " + goal + "\n\nТекущий путь:\n" + path
                    + "\n\nОцениваемый шаг: " + candidate.getContent();
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(SELF_SCORE_SYSTEM),
                    new UserMessage(userMsg)
            ))).call().content();

            Map<String, Object> result = parseJsonObject(response);
            return ((Number) result.getOrDefault("score", 0.5)).doubleValue();
        } catch (Exception e) {
            return 0.5;
        }
    }

    private double scoreCritic(RetrevalNode candidate, String goal, String path) {
        try {
            String userMsg = "Цель: " + goal + "\n\nКонтекст (путь):\n" + path
                    + "\n\nОцениваемый шаг: " + candidate.getContent();
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(CRITIC_SCORE_SYSTEM),
                    new UserMessage(userMsg)
            ))).call().content();

            Map<String, Object> result = parseJsonObject(response);
            return ((Number) result.getOrDefault("score", 0.5)).doubleValue();
        } catch (Exception e) {
            return 0.5;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private String buildPath(RetrevalTree tree) {
        List<RetrevalNode> path = tree.getValidatedPath();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            RetrevalNode node = path.get(i);
            sb.append(i).append(". ").append(node.getContent());
            if (node.getToolObservation() != null) {
                sb.append(" [obs: ").append(truncate(node.getToolObservation(), 100)).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String buildMemorySummary(SessionState session) {
        List<RetrevalPattern> memory = loadMemory(session);
        if (memory.isEmpty()) return "Нет накопленных паттернов.";

        StringBuilder sb = new StringBuilder();
        long successCount = memory.stream().filter(p -> "SUCCESS".equals(p.getType())).count();
        long failureCount = memory.stream().filter(p -> "FAILURE".equals(p.getType())).count();
        sb.append("Успешных: ").append(successCount).append(", неудачных: ").append(failureCount).append(".\n");

        memory.stream().filter(p -> "FAILURE".equals(p.getType())).limit(3).forEach(p ->
                sb.append("❌ [").append(p.getFailureType()).append("] ").append(truncate(p.getStepContent(), 80)).append("\n"));
        memory.stream().filter(p -> "SUCCESS".equals(p.getType())).limit(2).forEach(p ->
                sb.append("✓ ").append(truncate(p.getStepContent(), 80)).append("\n"));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<RetrevalPattern> loadMemory(SessionState session) {
        Object raw = session.getAttributes().get(MEMORY_KEY);
        if (raw == null) return new ArrayList<>();
        return objectMapper.convertValue(raw, new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private void addPattern(SessionState session, RetrevalPattern pattern) {
        List<RetrevalPattern> memory = loadMemory(session);
        memory.add(pattern);
        session.getAttributes().put(MEMORY_KEY, memory);
    }

    private void saveTree(SessionState session, RetrevalTree tree) {
        session.getAttributes().put(TREE_KEY, tree);
    }

    private RetrevalTree loadTree(SessionState session) {
        Object raw = session.getAttributes().get(TREE_KEY);
        if (raw == null) return RetrevalTree.withRoot("unknown");
        return objectMapper.convertValue(raw, RetrevalTree.class);
    }

    @SuppressWarnings("unchecked")
    private List<RetrevalNode> parseCandidates(String response, RetrevalTree tree) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);

            List<Map<String, Object>> rawList = objectMapper.readValue(cleaned, new TypeReference<>() {
            });
            List<RetrevalNode> candidates = new ArrayList<>();

            for (Map<String, Object> raw : rawList) {
                String content = (String) raw.getOrDefault("content", "шаг рассуждения");
                String toolName = (String) raw.get("toolName");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = raw.get("toolParameters") instanceof Map
                        ? (Map<String, Object>) raw.get("toolParameters") : new HashMap<>();

                RetrevalNode node = new RetrevalNode(content,
                        tree.getCurrentNodeId(),
                        tree.getCurrent() != null ? tree.getCurrent().getDepth() + 1 : 1);
                if (toolName != null && !toolName.isBlank() && !"null".equals(toolName)) {
                    node.setToolName(toolName);
                    node.setToolParameters(params);
                }
                candidates.add(node);
            }
            return candidates;
        } catch (Exception e) {
            log.warn("[ReTreVal] Failed to parse candidates: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            return objectMapper.readValue(cleaned, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String formatTools(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
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
        if (intent != null && !intent.getIntents().isEmpty()) {
            return intent.getIntents().get(0).getName();
        }
        return "unknown";
    }
}
