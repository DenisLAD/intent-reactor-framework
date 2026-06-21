package com.intentreactor.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/**
 * An immutable description of a tool invocation decided by the planner.
 *
 * <p>Carried by {@link PlanStep#action()} for steps of type {@link StepType#ACT}.
 * The execution engine resolves the named tool via {@link ToolProvider} and
 * calls {@link Tool#execute} with a {@link ToolInput} built from {@link #parameters()}.
 *
 * <p>Use {@link SimpleAction} as the standard implementation.
 *
 * @see PlanStep
 * @see SimpleAction
 * @see PerformedAction
 */
@JsonDeserialize(as = SimpleAction.class)
public interface Action {

    /**
     * Returns the name of the tool to invoke, matching {@link Tool#getName()}.
     *
     * @return the tool name; never {@code null}
     */
    String toolName();

    /**
     * Returns the parameters the planner has resolved for this invocation.
     *
     * @return parameter map; never {@code null}, may be empty
     */
    Map<String, Object> parameters();
}
