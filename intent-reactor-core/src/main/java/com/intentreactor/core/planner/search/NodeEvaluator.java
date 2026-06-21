package com.intentreactor.core.planner.search;

import com.intentreactor.api.Action;
import com.intentreactor.api.ToolResult;

import java.util.List;

public interface NodeEvaluator {

    List<Action> generateActions(SearchNode node);

    double evaluate(SearchNode node, ToolResult simulationResult);
}
