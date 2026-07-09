Agent goal: {goal}

Context:
{context}

Candidate next action:
- Tool: {toolName}
- Parameters: {parameters}
- Predicted outcome: {prediction}

Evaluate how well this action advances the agent toward its goal. Consider:
1. How relevant is the predicted outcome to the goal?
2. Will this action create new problems?
3. Is this the most efficient action in the current context?

Return strict JSON with no markdown fences:
{
  "score": 0.75,
  "reasoning": "brief explanation of the score"
}

Score from 0.0 (useless) to 1.0 (perfect).
