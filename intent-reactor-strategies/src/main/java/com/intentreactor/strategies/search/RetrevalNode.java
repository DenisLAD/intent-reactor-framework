package com.intentreactor.strategies.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
public class RetrevalNode {

    private String id;
    private String content;
    private String parentId;
    private List<String> childIds = new ArrayList<>();
    private String state = "PENDING"; // PENDING | VALIDATED | FAILED
    private double selfScore;
    private double criticScore;
    private int depth;
    private String failureType;   // SCORE_TOO_LOW | REASONING_LOOP | TOOL_ERROR | EMPTY_RESULT
    private String failureContext;
    private String toolName;
    private Map<String, Object> toolParameters = new HashMap<>();
    private String toolObservation;

    public RetrevalNode() {
    }

    public RetrevalNode(String content, String parentId, int depth) {
        this.id = UUID.randomUUID().toString();
        this.content = content;
        this.parentId = parentId;
        this.depth = depth;
    }
}
