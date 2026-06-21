package com.intentreactor.api;

import java.util.List;
import java.util.Map;

/**
 * Standard immutable implementation of {@link Plan} for single-intent scenarios.
 *
 * <p>The built-in planners (ReACT, Reflexion, LATS) return one {@code SimplePlan} per
 * planning cycle, typically containing a single {@link SimplePlanStep}. Custom planners
 * should do the same.
 *
 * <pre>{@code
 * // ACT step
 * return new SimplePlan(List.of(
 *     SimplePlanStep.act(new SimpleAction("weather_tool", Map.of("city", "Paris")),
 *                        "Fetching Paris weather", false)
 * ));
 *
 * // Terminal step
 * return new SimplePlan(List.of(SimplePlanStep.done("The temperature in Paris is 18°C.")));
 * }</pre>
 *
 * @see Plan
 * @see SimplePlanStep
 * @see CompositePlan
 */
public class SimplePlan implements Plan {

    private final List<PlanStep> steps;
    private final Map<String, Object> metadata;

    /**
     * Creates a plan with the given steps and empty metadata.
     *
     * @param steps the steps in this plan; must not be {@code null}
     */
    public SimplePlan(List<PlanStep> steps) {
        this(steps, Map.of());
    }

    /**
     * Creates a plan with the given steps and metadata.
     *
     * @param steps    the steps in this plan; must not be {@code null}
     * @param metadata optional planner-specific metadata; must not be {@code null}
     */
    public SimplePlan(List<PlanStep> steps, Map<String, Object> metadata) {
        this.steps = List.copyOf(steps);
        this.metadata = metadata;
    }

    @Override
    public List<PlanStep> steps() {
        return steps;
    }

    /**
     * Returns {@code true} if any step in this plan has type {@link StepType#DONE}
     * or {@link StepType#FAIL}.
     */
    @Override
    public boolean isComplete() {
        return steps.stream().anyMatch(s -> s.type() == StepType.DONE || s.type() == StepType.FAIL);
    }

    @Override
    public Map<String, Object> metadata() {
        return metadata;
    }
}
