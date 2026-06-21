package com.intentreactor.api.event;

import com.intentreactor.api.PlanStep;

/**
 * Published immediately before a plan step is executed.
 *
 * <p>For {@code ACT} steps, this fires before the tool is invoked.
 * For terminal steps ({@code DONE}, {@code FAIL}), this fires before the response
 * is assembled. Use this event to implement per-step timeouts or detailed tracing.
 */
public class PlanStepStartedEvent extends IntentReactorEvent {

    private final PlanStep step;

    /**
     * Creates the event.
     *
     * @param source    the publishing component
     * @param sessionId the session identifier
     * @param step      the step about to be executed; must not be {@code null}
     */
    public PlanStepStartedEvent(Object source, String sessionId, PlanStep step) {
        super(source, sessionId);
        this.step = step;
    }

    /**
     * Returns the plan step that is about to be executed.
     */
    public PlanStep getStep() {
        return step;
    }
}
