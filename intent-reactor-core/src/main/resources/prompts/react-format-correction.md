Your previous response was invalid.
Error: {error}
Your response was: {badResponse}

You MUST respond with a single valid JSON object in ONE of these formats:
To call a tool:
{"toolName": "tool_name", "parameters": {"param": "value"}}
IMPORTANT: ALL parameters MUST be nested inside 'parameters', not at the top level.
WRONG:   {"directory": "./", "recursive": true}
CORRECT: {"toolName": "my_tool", "parameters": {"directory": "./", "recursive": true}}
When done: {"done": true, "finalMessage": "Summary of what was accomplished"}
If stuck: {"failed": true, "reason": "Explanation"}
Respond ONLY with the JSON object. Try again.
