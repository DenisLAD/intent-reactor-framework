package com.intentreactor.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.ConfirmationManager;
import com.intentreactor.api.ConfirmationRequest;
import com.intentreactor.api.ConfirmationResult;
import com.intentreactor.api.Intent;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.IntentPreprocessor;
import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.Message;
import com.intentreactor.api.MultiIntentContext;
import com.intentreactor.api.PerformedAction;
import com.intentreactor.api.Plan;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Planner;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.ReasoningStep;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;
import com.intentreactor.api.StepType;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.api.ToolResult;
import com.intentreactor.api.event.ConfirmationRequiredEvent;
import com.intentreactor.api.event.IntentAnalysisCompletedEvent;
import com.intentreactor.api.event.IntentAnalysisStartedEvent;
import com.intentreactor.api.event.PlanCompletedEvent;
import com.intentreactor.api.event.PlanFailedEvent;
import com.intentreactor.api.event.PlanStartedEvent;
import com.intentreactor.api.event.PlanStepCompletedEvent;
import com.intentreactor.api.event.PlanStepStartedEvent;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class IntentReactorServiceImpl implements IntentReactorService {

    private static final Logger log = LoggerFactory.getLogger(IntentReactorServiceImpl.class);
    private static final String MULTI_INTENT_STATE_KEY = "multiIntentState";

    private final IntentPreprocessor preprocessor;
    private final Planner planner;
    private final SessionStore sessionStore;
    private final ToolProvider toolProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final IntentReactorProperties properties;
    private final ConfirmationManager confirmationManager;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader = new PromptLoader();
    private final ExecutorService executorService;

    public IntentReactorServiceImpl(IntentPreprocessor preprocessor,
                                    Planner planner,
                                    SessionStore sessionStore,
                                    ToolProvider toolProvider,
                                    ApplicationEventPublisher eventPublisher,
                                    IntentReactorProperties properties,
                                    ConfirmationManager confirmationManager,
                                    ChatClient chatClient,
                                    ObjectMapper objectMapper) {
        this.preprocessor = preprocessor;
        this.planner = planner;
        this.sessionStore = sessionStore;
        this.toolProvider = toolProvider;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.confirmationManager = confirmationManager;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "intent-reactor-parallel-" + UUID.randomUUID().toString().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    @Override
    public ReactorResponse process(String message, Map<String, Object> context) {
        SessionState session = new SessionState(UUID.randomUUID().toString());
        if (context != null) session.getAttributes().putAll(context);
        return executeProcess(session, message, false);
    }

    @Override
    public ReactorResponse process(String sessionId, String message) {
        String resolvedId = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString() : sessionId;
        SessionState session = sessionStore.findById(resolvedId)
                .orElseGet(() -> new SessionState(resolvedId));
        boolean isFirstMessage = session.getMessages().isEmpty();
        boolean pinRequested = Boolean.TRUE.equals(
                session.getAttributes().remove(com.intentreactor.api.SessionAttributeKeys.PIN_NEXT_USER_MESSAGE));
        session.addMessage((isFirstMessage || pinRequested)
                ? Message.pinnedUser(message)
                : Message.user(message));
        sessionStore.save(session);
        return executeProcess(session, message, true);
    }

    @Override
    public ReactorResponse proceedAfterConfirmation(String sessionId, ConfirmationResult confirmation) {
        SessionState session = sessionStore.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (session.getPlanState().getStatus() != PlanStatus.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Session is not awaiting confirmation: " + sessionId);
        }

        // Check confirmation timeout using the exact moment confirmation was requested (D1)
        String reqAtStr = (String) session.getAttributes().get("confirmationRequestedAt");
        LocalDateTime requestedAt = reqAtStr != null ? LocalDateTime.parse(reqAtStr) : session.getUpdatedAt();
        LocalDateTime confirmationDeadline = requestedAt.plus(properties.getPlanning().getConfirmationTimeout());
        if (confirmationDeadline.isBefore(LocalDateTime.now())) {
            session.getPlanState().setStatus(PlanStatus.FAILED);
            sessionStore.save(session);
            eventPublisher.publishEvent(new PlanFailedEvent(this, sessionId, "Confirmation request expired"));
            return ReactorResponse.failed(sessionId, "Confirmation request has expired");
        }

        if (!confirmation.isApproved()) {
            session.getPlanState().setStatus(PlanStatus.FAILED);
            sessionStore.save(session);
            eventPublisher.publishEvent(new PlanFailedEvent(this, sessionId, "User rejected action"));
            return ReactorResponse.failed(sessionId, "Action was rejected by user");
        }

        if (confirmation.getModifiedParameters() != null && !confirmation.getModifiedParameters().isEmpty()) {
            session.getAttributes().put("pendingModifiedParameters", confirmation.getModifiedParameters());
        }

        session.getPlanState().setStatus(PlanStatus.RUNNING);
        sessionStore.save(session);

        ReactorResponse response = continueExecution(session, true);

        // After resuming a confirmed action, continue remaining sequential multi-intent intents if any
        MultiIntentContext ctx = getMultiIntentContext(session);
        if (ctx != null && response.getStatus() == PlanStatus.COMPLETED && !ctx.getPendingIntents().isEmpty()) {
            if (ctx.getCurrentIntent() != null) {
                ctx.getResults().put(ctx.getCurrentIntent().getName(), response);
                ctx.getCompletedIntents().add(ctx.getCurrentIntent());
            }
            return executeSequential(session, ctx, true);
        }

        return response;
    }

    @Override
    public SessionState getSessionState(String sessionId) {
        return sessionStore.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    // ---- Core execution flow ----

    private ReactorResponse executeProcess(SessionState session, String message, boolean persistent) {
        eventPublisher.publishEvent(new IntentAnalysisStartedEvent(this, session.getId(), message));

        IntentAnalysisResult intent = preprocessor.analyze(message, session, session.getAttributes());
        eventPublisher.publishEvent(new IntentAnalysisCompletedEvent(this, session.getId(), intent));

        String goal = intent.getReasoningSuggestion() != null ? intent.getReasoningSuggestion() : message;
        List<PlanStep> previousSteps = session.getPlanState() != null
                ? new ArrayList<>(session.getPlanState().getCompletedSteps())
                : new ArrayList<>();
        PlanState newPlanState = new PlanState(goal);
        newPlanState.getCompletedSteps().addAll(previousSteps);
        session.setPlanState(newPlanState);
        eventPublisher.publishEvent(new PlanStartedEvent(this, session.getId(), goal));

        if (persistent) sessionStore.save(session);

        // Guard: uncertain or empty result → treat as single intent (B1)
        if (intent.isUncertain() || !intent.hasIntents()) {
            return continueExecution(session, persistent);
        }

        // Save original intent so buildCurrentIntent() can restore it across planning iterations (B2)
        session.getAttributes().put("originalIntent", intent);

        // Multi-intent dispatch
        if (intent.getIntents().size() > 1) {
            return executeMultiIntent(session, intent, persistent);
        }

        return continueExecution(session, persistent);
    }

    private ReactorResponse continueExecution(SessionState session, boolean persistent) {
        List<PerformedAction> allActions = new ArrayList<>();
        int maxSteps = properties.getPlanning().getMaxSteps();

        // When resuming after user confirmation, the pendingStep was saved before the pause.
        // Execute it directly without re-planning to avoid an infinite confirmation loop.
        @SuppressWarnings("unchecked")
        PlanStep resumeStep = objectMapper.convertValue(session.getAttributes().remove("pendingStep"), PlanStep.class);
        if (resumeStep != null) {
            session.getAttributes().remove("confirmationRequestedAt");
            eventPublisher.publishEvent(new PlanStepStartedEvent(this, session.getId(), resumeStep));
            ToolResult resumeResult = executeTool(resumeStep, session);
            allActions.add(new PerformedAction(resumeStep.action().toolName(), resumeStep.action().parameters(), resumeResult));
            String resumeMsg = resumeResult.isSuccess()
                    ? "[TOOL_RESULT] " + resumeStep.action().toolName() + ": " + resumeResult.getData()
                    : "[TOOL_ERROR] " + resumeStep.action().toolName() + ": " + resumeResult.getErrorMessage();
            session.addMessage(Message.system(resumeMsg));
            session.getPlanState().addCompletedStep(resumeStep);
            eventPublisher.publishEvent(new PlanStepCompletedEvent(this, session.getId(), resumeStep, resumeResult));
            if (persistent) sessionStore.save(session);
        }

        for (int stepCount = 0; stepCount < maxSteps; stepCount++) {
            IntentAnalysisResult intent = buildCurrentIntent(session);
            Plan plan = planner.plan(session, intent);

            if (plan.steps().isEmpty()) {
                log.warn("Planner returned empty plan for session {}", session.getId());
                break;
            }

            for (PlanStep step : plan.steps()) {

                if (step.type() == StepType.DONE) {
                    String finalText = step.description();
                    session.getPlanState().setStatus(PlanStatus.COMPLETED);
                    session.addMessage(Message.assistant(finalText));
                    if (persistent) sessionStore.save(session);
                    eventPublisher.publishEvent(new PlanCompletedEvent(this, session.getId(), finalText));
                    ReactorResponse response = ReactorResponse.completed(session.getId(), finalText, allActions);
                    response.setReasoningSteps(buildReasoningSteps(session));
                    return response;
                }

                if (step.type() == StepType.FAIL) {
                    String reason = step.description();
                    session.getPlanState().setStatus(PlanStatus.FAILED);
                    if (persistent) sessionStore.save(session);
                    eventPublisher.publishEvent(new PlanFailedEvent(this, session.getId(), reason));
                    ReactorResponse failedResponse = ReactorResponse.failed(session.getId(), reason);
                    failedResponse.setReasoningSteps(buildReasoningSteps(session));
                    failedResponse.setActions(new ArrayList<>(allActions));
                    return failedResponse;
                }

                if (step.type() != StepType.ACT) {
                    eventPublisher.publishEvent(new PlanStepStartedEvent(this, session.getId(), step));
                    if (step.type() == StepType.REASON) {
                        // Store thought in session attributes (not messages) to keep LLM context clean
                        @SuppressWarnings("unchecked")
                        List<String> thoughts = (List<String>) session.getAttributes()
                                .computeIfAbsent("thoughts", k -> new ArrayList<>());
                        thoughts.add(step.description());
                    } else {
                        session.addMessage(Message.system("[" + step.type() + "] " + step.description()));
                    }
                    if (persistent) sessionStore.save(session);
                    continue;
                }

                // ACT step
                eventPublisher.publishEvent(new PlanStepStartedEvent(this, session.getId(), step));

                if (step.requiresConfirmation()) {
                    ConfirmationRequest request = confirmationManager.buildRequest(step);
                    session.getPlanState().setStatus(PlanStatus.AWAITING_CONFIRMATION);
                    session.getAttributes().put("pendingStep", step);
                    session.getAttributes().put("confirmationRequestedAt", LocalDateTime.now().toString());
                    if (persistent) sessionStore.save(session);
                    eventPublisher.publishEvent(new ConfirmationRequiredEvent(this, session.getId(), request));
                    ReactorResponse confirmResponse = ReactorResponse.awaitingConfirmation(session.getId(), request);
                    confirmResponse.setReasoningSteps(buildReasoningSteps(session));
                    confirmResponse.setActions(new ArrayList<>(allActions));
                    return confirmResponse;
                }

                ToolResult result = executeTool(step, session);
                allActions.add(new PerformedAction(step.action().toolName(), step.action().parameters(), result));

                String resultMessage = result.isSuccess()
                        ? "[TOOL_RESULT] " + step.action().toolName() + ": " + result.getData()
                        : "[TOOL_ERROR] " + step.action().toolName() + ": " + result.getErrorMessage();
                session.addMessage(Message.system(resultMessage));
                session.getPlanState().addCompletedStep(step);

                eventPublisher.publishEvent(new PlanStepCompletedEvent(this, session.getId(), step, result));
                if (persistent) sessionStore.save(session);
                break;
            } // end inner plan steps loop
        }

        session.getPlanState().setStatus(PlanStatus.FAILED);
        if (persistent) sessionStore.save(session);
        eventPublisher.publishEvent(new PlanFailedEvent(this, session.getId(), "Max steps exceeded"));
        ReactorResponse maxStepsResponse = ReactorResponse.failed(session.getId(), "Maximum number of steps exceeded");
        maxStepsResponse.setReasoningSteps(buildReasoningSteps(session));
        maxStepsResponse.setActions(new ArrayList<>(allActions));
        return maxStepsResponse;
    }

    // ---- Multi-intent ----

    private ReactorResponse executeMultiIntent(SessionState session, IntentAnalysisResult intent, boolean persistent) {
        String strategy = properties.getPlanning().getMultiIntent().getStrategy();
        MultiIntentContext ctx = loadOrCreateMultiIntentContext(session, intent, strategy);

        return switch (strategy) {
            case "llm-driven" -> executeLlmDriven(session, ctx, persistent);
            case "parallel" -> executeParallel(session, ctx, persistent);
            default -> executeSequential(session, ctx, persistent);
        };
    }

    private ReactorResponse executeSequential(SessionState session, MultiIntentContext ctx, boolean persistent) {
        while (!ctx.getPendingIntents().isEmpty()) {
            Intent current = ctx.getPendingIntents().remove(0);
            ctx.setCurrentIntent(current);

            session.setPlanState(new PlanState(current.getName()));
            session.getAttributes().put(MULTI_INTENT_STATE_KEY, ctx);
            if (persistent) sessionStore.save(session);

            ReactorResponse response = continueExecution(session, persistent);

            // Return early without marking complete — proceedAfterConfirmation will do it (C1)
            if (response.getStatus() == PlanStatus.AWAITING_CONFIRMATION) {
                return response;
            }

            ctx.getResults().put(current.getName(), response);
            ctx.getCompletedIntents().add(current);
        }

        return mergeSequentialResults(session.getId(), ctx);
    }

    private ReactorResponse executeLlmDriven(SessionState session, MultiIntentContext ctx, boolean persistent) {
        List<Intent> ordered = orderIntentsWithLlm(ctx.getPendingIntents());
        ctx.setPendingIntents(new ArrayList<>(ordered));
        return executeSequential(session, ctx, persistent);
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
                new TypeReference<List<String>>() {
                });
        List<Intent> ordered = new ArrayList<>();
        for (String name : names) {
            Intent found = byName.remove(name);
            if (found != null) ordered.add(found);
        }
        ordered.addAll(byName.values()); // intents missed by LLM → append at end
        return ordered;
    }

    private ReactorResponse executeParallel(SessionState session, MultiIntentContext ctx, boolean persistent) {
        List<Intent> pending = new ArrayList<>(ctx.getPendingIntents());
        ctx.getPendingIntents().clear();

        // Keep intentName paired with its future so failures can be recorded by name.
        List<Map.Entry<String, CompletableFuture<Map.Entry<String, ReactorResponse>>>> namedFutures =
                pending.stream()
                        .map(intentItem -> {
                            CompletableFuture<Map.Entry<String, ReactorResponse>> f =
                                    CompletableFuture.supplyAsync(() -> {
                                        SessionState isolated = cloneSession(session, intentItem.getName());
                                        ReactorResponse r = processSingleIntent(isolated, intentItem);
                                        return Map.entry(intentItem.getName(), r);
                                    }, executorService);
                            return Map.entry(intentItem.getName(), f);
                        })
                        .toList();

        for (Map.Entry<String, CompletableFuture<Map.Entry<String, ReactorResponse>>> nf : namedFutures) {
            String intentName = nf.getKey();
            CompletableFuture<Map.Entry<String, ReactorResponse>> f = nf.getValue();
            try {
                Map.Entry<String, ReactorResponse> entry = f.get(
                        (long) properties.getPlanning().getParallelTimeout().toMillis(),
                        TimeUnit.MILLISECONDS);
                ctx.getResults().put(entry.getKey(), entry.getValue());
            } catch (TimeoutException e) {
                log.warn("Parallel intent '{}' timed out", intentName);
                f.cancel(true);
                ctx.getResults().put(intentName, ReactorResponse.failed(session.getId(), "Parallel execution timed out"));
            } catch (InterruptedException | ExecutionException e) {
                log.error("Parallel intent '{}' failed", intentName, e);
                ctx.getResults().put(intentName, ReactorResponse.failed(session.getId(),
                        "Parallel execution failed: " + e.getMessage()));
            }
        }

        return mergeParallelResults(session.getId(), ctx);
    }

    private ReactorResponse processSingleIntent(SessionState session, Intent intentItem) {
        IntentAnalysisResult singleIntentResult = new IntentAnalysisResult();
        singleIntentResult.setIntents(List.of(intentItem));
        singleIntentResult.setReasoningSuggestion(intentItem.getName());
        session.setPlanState(new PlanState(intentItem.getName()));
        return continueExecution(session, false);
    }

    private SessionState cloneSession(SessionState original, String suffix) {
        SessionState clone = new SessionState(original.getId() + "-parallel-" + suffix.replaceAll("[^a-zA-Z0-9]", ""));
        clone.setMessages(new ArrayList<>(original.getMessages()));
        Map<String, Object> clonedAttrs = new HashMap<>(original.getAttributes());
        clonedAttrs.remove(MULTI_INTENT_STATE_KEY);
        clone.setAttributes(clonedAttrs);
        return clone;
    }

    private ReactorResponse mergeSequentialResults(String sessionId, MultiIntentContext ctx) {
        List<PerformedAction> allActions = new ArrayList<>();
        StringBuilder finalText = new StringBuilder();
        boolean anyFailed = false;

        for (Map.Entry<String, ReactorResponse> entry : ctx.getResults().entrySet()) {
            ReactorResponse r = entry.getValue();
            if (r.getStatus() == PlanStatus.FAILED) anyFailed = true;
            if (r.getActions() != null) allActions.addAll(r.getActions());
            if (r.getFinalText() != null) {
                if (!finalText.isEmpty()) finalText.append("; ");
                finalText.append("[").append(entry.getKey()).append("] ").append(r.getFinalText());
            }
        }

        return anyFailed
                ? ReactorResponse.failed(sessionId, "Some intents failed: " + finalText)
                : ReactorResponse.completed(sessionId, finalText.toString(), allActions);
    }

    private ReactorResponse mergeParallelResults(String sessionId, MultiIntentContext ctx) {
        return mergeSequentialResults(sessionId, ctx);
    }

    private MultiIntentContext loadOrCreateMultiIntentContext(SessionState session,
                                                              IntentAnalysisResult intent,
                                                              String strategy) {
        Object existing = session.getAttributes().get(MULTI_INTENT_STATE_KEY);
        if (existing instanceof MultiIntentContext ctx && !ctx.getPendingIntents().isEmpty()) {
            return ctx;
        }
        MultiIntentContext ctx = new MultiIntentContext(intent.getIntents(), strategy);
        session.getAttributes().put(MULTI_INTENT_STATE_KEY, ctx);
        return ctx;
    }

    private MultiIntentContext getMultiIntentContext(SessionState session) {
        Object obj = session.getAttributes().get(MULTI_INTENT_STATE_KEY);
        return obj instanceof MultiIntentContext ctx ? ctx : null;
    }

    // ---- Tool execution ----

    private ToolResult executeTool(PlanStep step, SessionState session) {
        String toolName = step.action().toolName();
        Map<String, Object> params = step.action().parameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> modified = (Map<String, Object>) session.getAttributes().remove("pendingModifiedParameters");
        if (modified != null && !modified.isEmpty()) {
            params = modified;
        }

        List<Tool> tools = toolProvider.getAvailableTools(session);
        Tool tool = tools.stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .orElse(null);

        if (tool == null) {
            return ToolResult.error("Unknown tool: " + toolName);
        }

        try {
            return tool.execute(new ToolInput(params, session.getId()));
        } catch (Exception e) {
            log.error("Tool {} threw exception", toolName, e);
            return ToolResult.error(e.getMessage());
        }
    }

    // ---- Helpers ----

    private IntentAnalysisResult buildCurrentIntent(SessionState session) {
        Object cached = session.getAttributes().get("originalIntent");
        if (cached instanceof IntentAnalysisResult original) {
            return original;
        }
        IntentAnalysisResult intent = new IntentAnalysisResult();
        if (session.getPlanState() != null && session.getPlanState().getGoalDescription() != null) {
            intent.setReasoningSuggestion(session.getPlanState().getGoalDescription());
        }
        return intent;
    }

    private List<ReasoningStep> buildReasoningSteps(SessionState session) {
        List<ReasoningStep> steps = new ArrayList<>();
        for (Message m : session.getMessages()) {
            if (m.getRole() == Message.Role.SYSTEM) {
                StepType type = StepType.OBSERVE;
                if (m.getContent().startsWith("[REFLECTION]")) type = StepType.REFLECT;
                else if (m.getContent().startsWith("[TOOL_RESULT]") || m.getContent().startsWith("[TOOL_ERROR]")) {
                    type = StepType.OBSERVE;
                }
                steps.add(new ReasoningStep(type, m.getContent(), m.getTimestamp()));
            }
        }
        return steps;
    }
}
