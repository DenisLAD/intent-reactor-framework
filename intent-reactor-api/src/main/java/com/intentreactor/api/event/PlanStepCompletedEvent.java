package com.intentreactor.api.event;

import com.intentreactor.api.PlanStep;
import com.intentreactor.api.ToolResult;

/**
 * Published after an {@code ACT} plan step has been executed and the tool has returned a result.
 *
 * <p>The event carries both the step that was executed and the {@link ToolResult}
 * returned by the tool. Use it for monitoring tool performance, detecting failures,
 * or logging individual tool invocations.
 */
public class PlanStepCompletedEvent extends IntentReactorEvent {

    private final PlanStep step;
    private final ToolResult result;

    /**
     * Creates the event.
     *
     * @param source    the publishing component
     * @param sessionId the session identifier
     * @param step      the step that was executed; must not be {@code null}
     * @param result    the tool result; must not be {@code null}
     */
    public PlanStepCompletedEvent(Object source, String sessionId,
                                  PlanStep step, ToolResult result) {
        super(source, sessionId);
        this.step = step;
        this.result = result;
    }

    /**
     * Returns the plan step that was executed.
     */
    public PlanStep getStep() {
        return step;
    }

    /**
     * Returns the result returned by the tool. Check {@link ToolResult#isSuccess()} for outcome.
     */
    public ToolResult getResult() {
        return result;
    }
}
