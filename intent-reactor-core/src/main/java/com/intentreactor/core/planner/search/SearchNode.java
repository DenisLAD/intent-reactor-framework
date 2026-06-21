package com.intentreactor.core.planner.search;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.intentreactor.api.Action;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class SearchNode {

    private String id;
    private Action action;
    @JsonIgnore
    private SearchNode parent;
    private List<SearchNode> children = new ArrayList<>();
    private int visits = 0;
    private double totalValue = 0.0;
    private Map<String, Object> state = new HashMap<>();

    public SearchNode() {
        this.id = UUID.randomUUID().toString();
    }

    public SearchNode(Action action, SearchNode parent) {
        this.id = UUID.randomUUID().toString();
        this.action = action;
        this.parent = parent;
    }

    public double ucbScore(double explorationConstant) {
        if (visits == 0) return Double.MAX_VALUE;
        double exploitation = totalValue / visits;
        double exploration = explorationConstant * Math.sqrt(Math.log(parent != null ? parent.visits : 1) / visits);
        return exploitation + exploration;
    }

    public double averageValue() {
        return visits == 0 ? 0 : totalValue / visits;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public void addChild(SearchNode child) {
        this.children.add(child);
    }

    public void update(double reward) {
        this.visits++;
        this.totalValue += reward;
    }
}
