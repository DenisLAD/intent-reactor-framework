package com.intentreactor.api;

/**
 * Marker interface for plan steps that can be executed in parallel with other
 * {@code ParallelStep} instances.
 *
 * <p>When the execution engine encounters consecutive {@code ParallelStep} instances
 * in a plan, it may execute them concurrently rather than sequentially.
 * Steps that do not implement this interface are always executed sequentially.
 *
 * @see PlanStep
 */
public interface ParallelStep extends PlanStep {
}
