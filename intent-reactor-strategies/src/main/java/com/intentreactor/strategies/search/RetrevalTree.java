package com.intentreactor.strategies.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class RetrevalTree {

    private Map<String, RetrevalNode> nodes = new LinkedHashMap<>();
    private String rootId;
    private String currentNodeId;

    public RetrevalTree() {
    }

    public static RetrevalTree withRoot(String goalContent) {
        RetrevalTree tree = new RetrevalTree();
        RetrevalNode root = new RetrevalNode(goalContent, null, 0);
        root.setState("VALIDATED");
        tree.nodes.put(root.getId(), root);
        tree.rootId = root.getId();
        tree.currentNodeId = root.getId();
        return tree;
    }

    public RetrevalNode getCurrent() {
        return nodes.get(currentNodeId);
    }

    /**
     * Walk the chain from root to currentNode (only VALIDATED nodes).
     */
    public List<RetrevalNode> getValidatedPath() {
        List<RetrevalNode> path = new ArrayList<>();
        RetrevalNode node = nodes.get(currentNodeId);
        while (node != null) {
            path.add(0, node);
            node = node.getParentId() != null ? nodes.get(node.getParentId()) : null;
        }
        return path;
    }
}
