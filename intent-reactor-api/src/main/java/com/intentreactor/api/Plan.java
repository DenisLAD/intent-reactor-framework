package com.intentreactor.api;

import java.util.List;
import java.util.Map;

/**
 * The output of a single {@link Planner#plan} call: an ordered list of steps to execute.
 *
 * <p>Planners return one {@code Plan} per cycle. The execution engine processes the first
 * {@link StepType#ACT} step it encounters, stores the observation in the session, and
 * calls the planner again. This loop continues until a {@link StepType#DONE} or
 * {@link StepType#FAIL} step is encountered or {@code max-steps} is reached.
 *
 * <p>Use {@link SimplePlan} as the standard single-intent implementation.
 * Use {@link CompositePlan} to sequence multiple sub-plans (multi-intent processing).
 *
 * @see PlanStep
 * @see SimplePlan
 * @see CompositePlan
 * @see Planner
 */
public interface Plan {

    /**
     * Returns the ordered list of steps in this plan.
     *
     * <p>The execution engine processes steps from the start of the list.
     * For {@link SimplePlan}, the list typically contains exactly one step per planner call.
     *
     * @return the step list; never {@code null}, may be empty
     */
    List<PlanStep> steps();

    /**
     * Returns {@code true} if this plan has reached a terminal state (DONE or FAIL).
     *
     * @return {@code true} when planning is complete
     */
    boolean isComplete();

    /**
     * Returns an optional map of planner-specific metadata attached to this plan.
     * Used by the LATS planner to carry MCTS scoring data.
     *
     * @return metadata map; never {@code null}, may be empty
     */
    Map<String, Object> metadata();
}
