package com.intentreactor.strategies.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Directed acyclic graph of thoughts for Graph-of-Thoughts (GoT) planner.
 * Serializable via Jackson for storage in SessionState.attributes.
 */
@Data
public class ThoughtGraph {

    private Map<String, ThoughtNode> nodes = new LinkedHashMap<>();
    private List<ThoughtEdge> edges = new ArrayList<>();
    private String rootId;
    private int operationCount = 0;

    public ThoughtGraph() {
    }

    public static ThoughtGraph withRoot(String goalContent) {
        ThoughtGraph g = new ThoughtGraph();
        ThoughtNode root = new ThoughtNode(goalContent, null, 0);
        g.nodes.put(root.getId(), root);
        g.rootId = root.getId();
        return g;
    }

    public ThoughtNode addNode(String content, String parentId) {
        ThoughtNode parent = nodes.get(parentId);
        int depth = parent != null ? parent.getDepth() + 1 : 0;
        ThoughtNode node = new ThoughtNode(content, parentId, depth);
        nodes.put(node.getId(), node);
        if (parent != null) {
            parent.getChildIds().add(node.getId());
            edges.add(new ThoughtEdge(parentId, node.getId(), "generates"));
        }
        return node;
    }

    public ThoughtNode aggregate(List<String> sourceIds, String aggregatedContent) {
        ThoughtNode agg = new ThoughtNode(aggregatedContent, null, 0);
        nodes.put(agg.getId(), agg);
        sourceIds.forEach(sid -> edges.add(new ThoughtEdge(sid, agg.getId(), "aggregates")));
        return agg;
    }

    public Optional<ThoughtNode> bestNode() {
        return nodes.values().stream()
                .filter(n -> !n.getId().equals(rootId))
                .max((a, b) -> Double.compare(a.getScore(), b.getScore()));
    }

    @Data
    public static class ThoughtEdge {
        private String fromId;
        private String toId;
        private String relation;

        public ThoughtEdge() {
        }

        public ThoughtEdge(String fromId, String toId, String relation) {
            this.fromId = fromId;
            this.toId = toId;
            this.relation = relation;
        }
    }
}
