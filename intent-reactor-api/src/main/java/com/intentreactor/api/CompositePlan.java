package com.intentreactor.api;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Plan} that sequences multiple sub-plans, used for multi-intent processing.
 *
 * <p>Created by {@code IntentReactorServiceImpl} when processing a request with
 * {@code planning.multi-intent.strategy=sequential}. Each sub-plan represents one intent
 * from {@link IntentAnalysisResult#getIntents()}. The composite plan exposes the steps
 * of the currently active sub-plan and advances to the next when the current one completes.
 *
 * <p>The active sub-plan index is tracked by {@link #getCurrentPlanIndex()} and advanced
 * via {@link #advanceToNextPlan()}.
 *
 * @see Plan
 * @see SimplePlan
 * @see MultiIntentContext
 */
@Getter
public class CompositePlan implements Plan {

    private final List<Plan> subPlans;
    private int currentPlanIndex = 0;

    /**
     * Creates a composite plan from the given list of sub-plans.
     *
     * @param subPlans the ordered sub-plans to execute; must not be {@code null}
     */
    public CompositePlan(List<Plan> subPlans) {
        this.subPlans = new ArrayList<>(subPlans);
    }

    /**
     * Returns the steps of the currently active sub-plan.
     * Returns an empty list when all sub-plans have been completed.
     */
    @Override
    public List<PlanStep> steps() {
        if (currentPlanIndex >= subPlans.size()) return List.of();
        return subPlans.get(currentPlanIndex).steps();
    }

    /**
     * Returns {@code true} when all sub-plans have reached a terminal state.
     */
    @Override
    public boolean isComplete() {
        return subPlans.stream().allMatch(Plan::isComplete);
    }

    @Override
    public Map<String, Object> metadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("subPlanCount", subPlans.size());
        meta.put("currentPlanIndex", currentPlanIndex);
        return meta;
    }

    /**
     * Advances execution to the next sub-plan.
     * Has no effect if the last sub-plan is already active.
     */
    public void advanceToNextPlan() {
        if (currentPlanIndex < subPlans.size() - 1) {
            currentPlanIndex++;
        }
    }
}
