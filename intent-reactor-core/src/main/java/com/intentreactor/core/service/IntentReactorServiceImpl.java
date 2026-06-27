package com.intentreactor.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.ConfirmationManager;
import com.intentreactor.api.ConfirmationRequest;
import com.intentreactor.api.ConfirmationResult;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.IntentPreprocessor;
import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.Message;
import com.intentreactor.api.MultiIntentContext;
import com.intentreactor.api.MultiIntentStrategy;
import com.intentreactor.api.PerformedAction;
import com.intentreactor.api.Plan;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Planner;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.ReasoningStep;
import com.intentreactor.api.SessionAttributeKeys;
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
import com.intentreactor.core.MessageMarkers;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Primary implementation of {@link com.intentreactor.api.IntentReactorService}.
 * Orchestrates intent preprocessing, planning, tool execution, confirmation flow,
 * and multi-intent dispatch.
 */
public class IntentReactorServiceImpl implements IntentReactorService {

    private static final Logger log = LoggerFactory.getLogger(IntentReactorServiceImpl.class);

    private final IntentPreprocessor preprocessor;
    private final Planner planner;
    private final SessionStore sessionStore;
    private final ToolProvider toolProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final IntentReactorProperties properties;
    private final ConfirmationManager confirmationManager;
    private final ObjectMapper objectMapper;
    private final Map<String, MultiIntentStrategy> strategyMap;

    public IntentReactorServiceImpl(IntentPreprocessor preprocessor,
                                    Planner planner,
                                    SessionStore sessionStore,
                                    ToolProvider toolProvider,
                                    ApplicationEventPublisher eventPublisher,
                                    IntentReactorProperties properties,
                                    ConfirmationManager confirmationManager,
                                    ObjectMapper objectMapper,
                                    List<MultiIntentStrategy> strategies) {
        this.preprocessor = preprocessor;
        this.planner = planner;
        this.sessionStore = sessionStore;
        this.toolProvider = toolProvider;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.confirmationManager = confirmationManager;
        this.objectMapper = objectMapper;
        this.strategyMap = new HashMap<>();
        for (MultiIntentStrategy s : strategies) {
            strategyMap.put(s.name(), s);
        }
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
                session.getAttributes().remove(SessionAttributeKeys.PIN_NEXT_USER_MESSAGE));
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

        // Check confirmation timeout using the exact moment confirmation was requested
        String reqAtStr = (String) session.getAttributes().get(CoreSessionKeys.CONFIRMATION_REQUESTED_AT);
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
            session.getAttributes().put(CoreSessionKeys.PENDING_MODIFIED_PARAMS, confirmation.getModifiedParameters());
        }

        session.getPlanState().setStatus(PlanStatus.RUNNING);
        sessionStore.save(session);

        ReactorResponse response = continueExecution(session, true);

        // After resuming a confirmed action, continue remaining sequential multi-intent intents if any
        Object ctxObj = session.getAttributes().get(SessionAttributeKeys.MULTI_INTENT_STATE_KEY);
        MultiIntentContext ctx = ctxObj instanceof MultiIntentContext c ? c : null;
        if (ctx != null && response.getStatus() == PlanStatus.COMPLETED && !ctx.getPendingIntents().isEmpty()) {
            if (ctx.getCurrentIntent() != null) {
                ctx.getResults().put(ctx.getCurrentIntent().getName(), response);
                ctx.getCompletedIntents().add(ctx.getCurrentIntent());
            }
            return strategyMap.get("sequential").execute(session, ctx, true, this::continueExecution);
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

        // Guard: uncertain or empty result → treat as single intent
        if (intent.isUncertain() || !intent.hasIntents()) {
            return continueExecution(session, persistent);
        }

        // Save original intent so buildCurrentIntent() can restore it across planning iterations
        session.getAttributes().put(CoreSessionKeys.ORIGINAL_INTENT, intent);

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
        PlanStep resumeStep = objectMapper.convertValue(
                session.getAttributes().remove(CoreSessionKeys.PENDING_STEP), PlanStep.class);
        if (resumeStep != null) {
            session.getAttributes().remove(CoreSessionKeys.CONFIRMATION_REQUESTED_AT);
            eventPublisher.publishEvent(new PlanStepStartedEvent(this, session.getId(), resumeStep));
            ToolResult resumeResult = executeTool(resumeStep, session);
            allActions.add(new PerformedAction(resumeStep.action().toolName(), resumeStep.action().parameters(), resumeResult));
            String resumeMsg = resumeResult.isSuccess()
                    ? MessageMarkers.TOOL_RESULT + " " + resumeStep.action().toolName() + ": " + resumeResult.getData()
                    : MessageMarkers.TOOL_ERROR + " " + resumeStep.action().toolName() + ": " + resumeResult.getErrorMessage();
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
                                .computeIfAbsent(CoreSessionKeys.THOUGHTS, k -> new ArrayList<>());
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
                    session.getAttributes().put(CoreSessionKeys.PENDING_STEP, step);
                    session.getAttributes().put(CoreSessionKeys.CONFIRMATION_REQUESTED_AT, LocalDateTime.now().toString());
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
                        ? MessageMarkers.TOOL_RESULT + " " + step.action().toolName() + ": " + result.getData()
                        : MessageMarkers.TOOL_ERROR + " " + step.action().toolName() + ": " + result.getErrorMessage();
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

        Object existing = session.getAttributes().get(SessionAttributeKeys.MULTI_INTENT_STATE_KEY);
        MultiIntentContext ctx;
        if (existing instanceof MultiIntentContext existingCtx && !existingCtx.getPendingIntents().isEmpty()) {
            ctx = existingCtx;
        } else {
            ctx = new MultiIntentContext(intent.getIntents(), strategy);
            session.getAttributes().put(SessionAttributeKeys.MULTI_INTENT_STATE_KEY, ctx);
        }

        MultiIntentStrategy ms = strategyMap.get(strategy);
        if (ms == null) ms = strategyMap.get("sequential");
        return ms.execute(session, ctx, persistent, this::continueExecution);
    }

    // ---- Tool execution ----

    private ToolResult executeTool(PlanStep step, SessionState session) {
        String toolName = step.action().toolName();
        Map<String, Object> params = step.action().parameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> modified = (Map<String, Object>) session.getAttributes()
                .remove(CoreSessionKeys.PENDING_MODIFIED_PARAMS);
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
        Object cached = session.getAttributes().get(CoreSessionKeys.ORIGINAL_INTENT);
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
                if (m.getContent().startsWith(MessageMarkers.REFLECTION)) type = StepType.REFLECT;
                else if (m.getContent().startsWith(MessageMarkers.TOOL_RESULT)
                        || m.getContent().startsWith(MessageMarkers.TOOL_ERROR)) {
                    type = StepType.OBSERVE;
                }
                steps.add(new ReasoningStep(type, m.getContent(), m.getTimestamp()));
            }
        }
        return steps;
    }
}
