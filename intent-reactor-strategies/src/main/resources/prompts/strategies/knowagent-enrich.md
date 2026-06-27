You are a knowledge engineering expert. Analyze the available tools and enrich their metadata with preconditions, postconditions, and contraindications.

For each tool, define:
- preconditions: what must be true before using this tool
- postconditions: what becomes true after successful use
- contraindications: when NOT to use this tool

Return a JSON array:
[
  {
    "toolName": "tool_name",
    "preconditions": ["Condition 1", "Condition 2"],
    "postconditions": ["Result 1"],
    "contraindications": ["When not to use"]
  }
]
