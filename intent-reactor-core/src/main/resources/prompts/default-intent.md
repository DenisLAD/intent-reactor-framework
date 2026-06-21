Analyze the user's message and extract their intent(s) and any named entities.

Return your analysis as JSON with this exact structure:
{
"intents": [
{ "name": "intent_name", "confidence": 0.95, "attributes": {} }
],
"entities": [
{ "type": "entity_type", "value": "entity_value", "metadata": {} }
],
"uncertain": false,
"reasoningSuggestion": "Brief description of what needs to be accomplished"
}

Rules:

- Split into MULTIPLE intents ONLY when the user clearly requests several INDEPENDENT goals in one message (e.g., "book
  a flight AND set a reminder"). A single task with sub-steps (e.g., "write a script and run it to calculate 2+2", "
  create a tool and use it") is ONE intent, not many.
- When in doubt whether to split, default to ONE intent.
- Confidence is a number between 0 and 1
- Entity types: date, time, city, person, product, number, etc.
- If the intent is unclear, set uncertain to true — but still provide one best-guess intent rather than an empty list
- reasoningSuggestion should be a clear, actionable goal description
