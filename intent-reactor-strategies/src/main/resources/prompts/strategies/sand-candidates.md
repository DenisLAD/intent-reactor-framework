Agent goal: {goal}

Recent context:
{context}

Available tools:
{tools}

The delegate planner proposes:

- Tool: {excludedTool}
- Parameters: {excludedParams}

Generate exactly {count} alternative actions that are DIFFERENT from the proposed tool or parameters above. Each
candidate must be a concrete step toward the goal using only tools listed above.

Return a strict JSON array with no markdown fences, comments, or explanations:
[
{
"toolName": "tool_name",
"parameters": {"param1": "value1"},
"reasoning": "brief rationale for why this step is useful"
}
]
