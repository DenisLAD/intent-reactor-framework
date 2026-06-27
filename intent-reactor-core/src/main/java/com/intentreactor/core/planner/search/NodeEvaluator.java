package com.intentreactor.core.planner.search;

import com.intentreactor.api.Action;
import com.intentreactor.api.ToolResult;

import java.util.List;

/**
 * SPI for MCTS node evaluation within {@link com.intentreactor.core.planner.LATSPlanner}:
 * generates candidate actions for a node and scores the outcome of a simulated action.
 */
public interface NodeEvaluator {

    List<Action> generateActions(SearchNode node);

    double evaluate(SearchNode node, ToolResult simulationResult);
}
