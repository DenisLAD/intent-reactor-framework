Agent goal: {goal}

Context:
{context}

Action the agent is about to take:
- Tool: {toolName}
- Parameters: {parameters}

Predict the outcome of executing this action. What will the tool return? Will the operation succeed? What will change in the task state?

Return strict JSON with no markdown fences:
{
  "predicted_result": "brief description of expected outcome",
  "confidence": 0.8
}
