package com.intentreactor.core.planner.search;

import com.intentreactor.api.Action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DefaultSearchTree implements SearchTree {

    private final SearchNode root;
    private final int branchingFactor;

    public DefaultSearchTree(String goalDescription, int branchingFactor) {
        this.root = new SearchNode();
        this.root.getState().put("goal", goalDescription);
        this.root.update(0);
        this.branchingFactor = branchingFactor;
    }

    @Override
    public SearchNode getRoot() {
        return root;
    }

    /**
     * Selects a leaf for expansion. Returns a not-fully-expanded interior node
     * (fewer children than branchingFactor) or a leaf node following max-UCB path.
     */
    @Override
    public SearchNode selectLeaf(double explorationConstant) {
        SearchNode current = root;
        while (!current.isLeaf()) {
            if (current.getChildren().size() < branchingFactor) {
                return current;
            }
            current = current.getChildren().stream()
                    .max(Comparator.comparingDouble(n -> n.ucbScore(explorationConstant)))
                    .orElse(current);
        }
        return current;
    }

    @Override
    public void expand(SearchNode node, List<Action> actions) {
        for (Action action : actions) {
            SearchNode child = new SearchNode(action, node);
            // Propagate parent state (including goal and history) to child nodes
            child.getState().putAll(node.getState());
            node.addChild(child);
        }
    }

    @Override
    public void backpropagate(SearchNode node, double reward) {
        SearchNode current = node;
        while (current != null) {
            current.update(reward);
            current = current.getParent();
        }
    }

    @Override
    public List<SearchNode> bestPath() {
        List<SearchNode> path = new ArrayList<>();
        SearchNode current = findBestLeaf(root);
        while (current != null && current != root) {
            path.add(0, current);
            current = current.getParent();
        }
        return path;
    }

    private SearchNode findBestLeaf(SearchNode node) {
        if (node.isLeaf()) return node;
        // Always recurse through the child with the highest averageValue — standard MCTS best-path extraction.
        return node.getChildren().stream()
                .max(Comparator.comparingDouble(SearchNode::averageValue))
                .map(this::findBestLeaf)
                .orElse(node);
    }
}
