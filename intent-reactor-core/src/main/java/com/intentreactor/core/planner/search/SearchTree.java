package com.intentreactor.core.planner.search;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.intentreactor.api.Action;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(@JsonSubTypes.Type(value = DefaultSearchTree.class, name = "default"))
public interface SearchTree {

    SearchNode getRoot();

    SearchNode selectLeaf(double explorationConstant);

    void expand(SearchNode node, List<Action> actions);

    void backpropagate(SearchNode node, double reward);

    List<SearchNode> bestPath();
}
