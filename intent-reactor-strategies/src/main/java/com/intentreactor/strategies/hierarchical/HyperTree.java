package com.intentreactor.strategies.hierarchical;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Data
public class HyperTree {

    private Map<String, HyperNode> nodes = new LinkedHashMap<>();
    private String rootId;
    private String currentNodeId;

    public HyperTree() {
    }

    public static HyperTree withRoot(String goalContent) {
        HyperTree tree = new HyperTree();
        HyperNode root = new HyperNode(goalContent, List.of(), null, 0);
        root.setStatus("DONE"); // root is a placeholder, not executed
        tree.nodes.put(root.getId(), root);
        tree.rootId = root.getId();
        return tree;
    }

    public HyperNode getCurrent() {
        return currentNodeId != null ? nodes.get(currentNodeId) : null;
    }

    /**
     * BFS to find the first PENDING node.
     */
    public Optional<HyperNode> nextPendingNode() {
        List<String> queue = new ArrayList<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String id = queue.remove(0);
            HyperNode node = nodes.get(id);
            if (node == null) continue;
            if ("PENDING".equals(node.getStatus())) return Optional.of(node);
            queue.addAll(node.getChildIds());
        }
        return Optional.empty();
    }
}
