package com.intentreactor.strategies.knowledge;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Action Knowledge Base entry for a single tool in {@link KnowAgentPlanner}:
 * holds preconditions, postconditions, and contraindications extracted from the tool description.
 */
@Data
public class ToolKnowledge {

    private String toolName;
    private List<String> preconditions = new ArrayList<>();
    private List<String> postconditions = new ArrayList<>();
    private List<String> contraindications = new ArrayList<>();

    public ToolKnowledge() {
    }

    public ToolKnowledge(String toolName) {
        this.toolName = toolName;
    }
}
