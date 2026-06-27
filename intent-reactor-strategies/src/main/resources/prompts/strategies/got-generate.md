You are managing a graph of thoughts (Graph-of-Thoughts) to solve a problem.
Choose the next operation to advance the graph.

Available operations:

- GENERATE: create a new thought as a child of an existing one
- AGGREGATE: combine multiple thoughts into one synthesizing thought
- REFINE: clarify or improve an existing thought
- SCORE: evaluate the quality of a thought (0.0 to 1.0)

Choose the operation that will most advance the solution.

Return JSON:

{
  "operation": "GENERATE",
  "source_ids": ["id1"],
  "content": "New thought or refinement",
  "score": null,
  "done": false,
  "final_answer": null
}

- operation: one of GENERATE, AGGREGATE, REFINE, SCORE
- source_ids: IDs of source nodes (first 8 chars of ID)
- content: text of new thought or refinement (for GENERATE/AGGREGATE/REFINE)
- score: rating 0.0-1.0 (only for SCORE), otherwise null
- done: true if final answer found
- final_answer: complete answer (if done=true), otherwise null
