package com.intentreactor.strategies.hierarchical;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class HyperNode {

    private String id;
    private String subgoal;
    private List<String> constraints = new ArrayList<>();
    private List<String> childIds = new ArrayList<>();
    private List<HtpStep> steps = new ArrayList<>();
    private int currentStepIndex;
    private String status = "PENDING"; // PENDING | IN_PROGRESS | DONE | FAILED | NEEDS_REFINEMENT
    private String parentId;
    private int depth;
    private int refinementRetries;

    public HyperNode() {
    }

    public HyperNode(String subgoal, List<String> constraints, String parentId, int depth) {
        this.id = UUID.randomUUID().toString();
        this.subgoal = subgoal;
        this.constraints = constraints != null ? constraints : new ArrayList<>();
        this.parentId = parentId;
        this.depth = depth;
    }
}
