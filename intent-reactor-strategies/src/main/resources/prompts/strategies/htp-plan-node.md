You are a tactical planner. Create a concrete execution plan for the given sub-goal.

Sub-goal: {subgoal}
Constraints: {constraints}
Refinement context (if any): {refinementContext}

Available tools:
{tools}

Create a plan of at most {maxSteps} steps to achieve this sub-goal.

Return a JSON array of steps:
[
  {"action": "Step description", "toolName": "tool_name", "parameters": {"param": "value"}}
]
