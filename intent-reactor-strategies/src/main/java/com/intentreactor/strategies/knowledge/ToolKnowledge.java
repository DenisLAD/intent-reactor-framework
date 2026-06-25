package com.intentreactor.strategies.knowledge;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
