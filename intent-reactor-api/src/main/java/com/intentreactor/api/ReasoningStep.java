package com.intentreactor.api;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A single entry in the reasoning trace recorded during a planning cycle.
 *
 * <p>Collected in {@link ReactorResponse#getReasoningSteps()} for every step the planner
 * produced (including REASON, OBSERVE, and REFLECT steps, not just ACT steps).
 * Useful for debugging, explainability, and audit trails.
 *
 * @see ReactorResponse#getReasoningSteps()
 * @see StepType
 */
@Getter
@Setter
public class ReasoningStep {

    private StepType type;
    private String description;
    private LocalDateTime timestamp;

    /**
     * Required by Jackson for deserialization.
     */
    public ReasoningStep() {
    }

    /**
     * Creates a reasoning trace entry.
     *
     * @param type        the kind of step in the ReACT cycle
     * @param description the content of this step (LLM reasoning, tool result, reflection text)
     * @param timestamp   when this step occurred
     */
    public ReasoningStep(StepType type, String description, LocalDateTime timestamp) {
        this.type = type;
        this.description = description;
        this.timestamp = timestamp;
    }

}
