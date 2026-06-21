package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the progress of a planning cycle within a {@link SessionState}.
 *
 * <p>Created by the execution engine when a session receives its first message and
 * persisted as part of {@link SessionState}. Inspect it via
 * {@link SessionState#getPlanState()} to check goal description, current status,
 * and the list of steps already completed.
 *
 * @see SessionState
 * @see PlanStatus
 */
@Getter
@Setter
public class PlanState {

    private String goalDescription;
    private PlanStatus status = PlanStatus.RUNNING;
    private List<PlanStep> completedSteps = new ArrayList<>();
    private int currentStepIndex = 0;
    private int retryCount = 0;

    /**
     * Required by Jackson for deserialization.
     */
    public PlanState() {
    }

    /**
     * Creates an initial plan state for the given goal.
     *
     * @param goalDescription the natural-language description of what the session aims to achieve
     */
    public PlanState(String goalDescription) {
        this.goalDescription = goalDescription;
    }

    /**
     * Records a completed step and advances the step index.
     *
     * @param step the step that was just executed
     */
    public void addCompletedStep(PlanStep step) {
        this.completedSteps.add(step);
        this.currentStepIndex++;
    }
}
