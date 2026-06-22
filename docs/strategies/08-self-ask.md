# Self-Ask Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `self-ask`

---

## Summary

Self-Ask decomposes a complex question into a sequence of simpler sub-questions, answers each one (potentially using tools), and combines the intermediate answers into a final response. It is well-suited for multi-hop factual reasoning where each step depends on the previous one.

---

## How It Works

1. **Decomposition** — the LLM is given the original question and asked: *"Are there any follow-up questions you need to answer first?"* If yes, it lists the sub-questions.
2. **Sub-question loop** — for each sub-question, the planner runs a mini ReACT pass:
   - The LLM may answer from its own knowledge or invoke a tool.
   - The sub-answer is recorded.
3. **Final answer** — all sub-questions and their answers are assembled into context. The LLM produces the final answer to the original question.
4. A `DONE` step is emitted.

Self-Ask is a decorator over ReACT and inherits all tool-access capabilities.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: self-ask
    max-steps: 50   # shared limit across all sub-question passes
```

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| Decomposition prompt | `prompts/self-ask-decompose.md` | Generates sub-questions |
| Sub-answer prompt | `prompts/react-system.md` | Mini ReACT for each sub-question |
| Synthesis prompt | `prompts/self-ask-synthesize.md` | Final answer from sub-answers |

---

## When to Use

- Multi-hop factual questions: *"Who is the CEO of the company that makes the product used in X?"*
- Research tasks requiring sequential information gathering.
- Any scenario where the answer to the original question cannot be determined without resolving dependencies first.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Accurate on multi-hop reasoning | More LLM calls than flat ReACT |
| Sub-questions can invoke tools | Decomposition quality depends on the LLM |
| Transparent reasoning chain | May generate unnecessary sub-questions |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: self-ask
```

Question: *"What year was the programming language created by the inventor of the World Wide Web released?"*

Sub-question 1: *"Who invented the World Wide Web?"* → *"Tim Berners-Lee"*

Sub-question 2: *"What programming language did Tim Berners-Lee create?"* → *"He did not create a mainstream programming language; this premise may be flawed."*

Final answer: *"Tim Berners-Lee is not known for creating a programming language; he is known for creating HTTP, HTML, and the Web itself."*
