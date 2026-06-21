package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of one call to {@link IntentReactorService#process} or
 * {@link IntentReactorService#proceedAfterConfirmation}.
 *
 * <p>Inspect {@link #getStatus()} first to determine the outcome category:
 * <pre>{@code
 * ReactorResponse response = reactor.process("session-1", "Cancel order 42");
 *
 * switch (response.getStatus()) {
 *     case COMPLETED -> {
 *         ui.display(response.getFinalText());
 *         log.info("Tool calls: {}", response.getActions().size());
 *     }
 *     case AWAITING_CONFIRMATION -> {
 *         ConfirmationRequest req = response.getConfirmationRequest();
 *         boolean approved = ui.confirm(req.getDescription());
 *         reactor.proceedAfterConfirmation(
 *             response.getSessionId(),
 *             approved ? ConfirmationResult.approve() : ConfirmationResult.reject()
 *         );
 *     }
 *     case FAILED ->
 *         ui.displayError(response.getFinalText());
 * }
 * }</pre>
 *
 * <p>Use the static factory methods when implementing a custom
 * {@code IntentReactorService}.
 *
 * @see PlanStatus
 * @see PerformedAction
 * @see ReasoningStep
 * @see ConfirmationRequest
 */
@Getter
@Setter
public class ReactorResponse {

    private String finalText;
    private List<PerformedAction> actions = new ArrayList<>();
    private boolean requiresConfirmation;
    private ConfirmationRequest confirmationRequest;
    private List<ReasoningStep> reasoningSteps = new ArrayList<>();
    private String sessionId;
    private PlanStatus status;

    /**
     * Required by Jackson for deserialization.
     */
    public ReactorResponse() {
    }

    /**
     * Creates a response representing successful plan completion.
     *
     * @param sessionId the identifier of the session
     * @param finalText the natural-language summary from the planner's DONE step
     * @param actions   the tool invocations that occurred during the plan
     * @return a non-null response with {@code status=COMPLETED}
     */
    public static ReactorResponse completed(String sessionId, String finalText,
                                            List<PerformedAction> actions) {
        ReactorResponse r = new ReactorResponse();
        r.sessionId = sessionId;
        r.finalText = finalText;
        r.actions = actions;
        r.status = PlanStatus.COMPLETED;
        return r;
    }

    /**
     * Creates a response representing a plan that could not be completed.
     *
     * @param sessionId the identifier of the session
     * @param reason    a human-readable explanation of why planning failed
     * @return a non-null response with {@code status=FAILED}
     */
    public static ReactorResponse failed(String sessionId, String reason) {
        ReactorResponse r = new ReactorResponse();
        r.sessionId = sessionId;
        r.finalText = reason;
        r.status = PlanStatus.FAILED;
        return r;
    }

    /**
     * Creates a response representing a plan paused pending user confirmation.
     *
     * @param sessionId the session identifier (pass back to
     *                  {@link IntentReactorService#proceedAfterConfirmation})
     * @param request   details of the action requiring confirmation
     * @return a non-null response with {@code status=AWAITING_CONFIRMATION}
     */
    public static ReactorResponse awaitingConfirmation(String sessionId,
                                                       ConfirmationRequest request) {
        ReactorResponse r = new ReactorResponse();
        r.sessionId = sessionId;
        r.requiresConfirmation = true;
        r.confirmationRequest = request;
        r.status = PlanStatus.AWAITING_CONFIRMATION;
        return r;
    }

}
