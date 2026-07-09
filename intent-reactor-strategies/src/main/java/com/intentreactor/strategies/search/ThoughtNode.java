package com.intentreactor.strategies.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A node in the Tree-of-Thoughts / Graph-of-Thoughts search space.
 * Serializable via Jackson for storage in SessionState.attributes.
 */
@Data
public class ThoughtNode {

    private String id = UUID.randomUUID().toString();
    private String content;
    private double score = 0.0;
    private int depth = 0;
    private String parentId;
    private List<String> childIds = new ArrayList<>();
    private boolean terminal = false;
    private boolean exhausted = false;

    public ThoughtNode() {
    }

    public ThoughtNode(String content, String parentId, int depth) {
        this.content = content;
        this.parentId = parentId;
        this.depth = depth;
    }
}
