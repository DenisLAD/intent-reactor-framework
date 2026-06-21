package com.intentreactor.api.event;

/**
 * Published when the execution engine begins a new planning cycle for a session.
 *
 * <p>This event fires once per {@code IntentReactorService.process()} call,
 * immediately after intent analysis and before the first call to the planner.
 * Use it to start timing a planning cycle or to log the user's goal.
 */
public class PlanStartedEvent extends IntentReactorEvent {

    private final String goalDescription;

    /**
     * Creates the event.
     *
     * @param source          the publishing component
     * @param sessionId       the session identifier
     * @param goalDescription the natural-language goal derived from intent analysis
     */
    public PlanStartedEvent(Object source, String sessionId, String goalDescription) {
        super(source, sessionId);
        this.goalDescription = goalDescription;
    }

    /**
     * Returns the natural-language goal the planner is working towards.
     */
    public String getGoalDescription() {
        return goalDescription;
    }
}
