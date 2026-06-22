# STORM Strategy

**Module:** `intent-reactor-strategies`
**Strategy value:** `storm`

---

## Summary

STORM (Synthesis of Topic Outlines through Retrieval and Multi-perspective questioning) generates comprehensive, Wikipedia-style articles or reports by simulating a panel of expert perspectives. Each simulated expert asks questions from their domain angle, the answers are researched (via tools or the LLM's knowledge), and all insights are synthesized into a structured long-form document.

---

## How It Works

**Phase 1 — Perspective generation**
The LLM generates `max-perspectives` distinct expert personas relevant to the topic. For *"Climate change impacts on agriculture"*, personas might be: agronomist, economist, policy analyst, climate scientist, farmer.

**Phase 2 — Expert questioning**
Each persona independently generates `questions-per-perspective` questions from their viewpoint. Questions tend to be non-overlapping because each expert cares about different aspects.

**Phase 3 — Research and answering**
Each question is answered by a mini ReACT loop. Tools (web fetch, knowledge_search, file reading) are fully available. Answers are recorded per question.

**Phase 4 — Outline synthesis**
The LLM assembles the research into a structured outline: sections, headings, key points.

**Phase 5 — Article writing**
Each section is written in sequence, drawing on the relevant Q&A pairs. A final pass produces a coherent, well-structured document.

---

## Configuration

```yaml
intent-reactor:
  planning:
    strategy: storm
    storm:
      max-perspectives: 5          # number of expert personas generated
      questions-per-perspective: 3 # questions each persona asks
    max-steps: 100   # increase for topics with many questions
```

Total research LLM calls: approximately `max-perspectives × questions-per-perspective`. Long reports may require `max-steps` above 100.

---

## Prompts

| Prompt | Classpath path | Purpose |
|---|---|---|
| Perspective generation | `prompts/storm-perspectives.md` | Creates expert personas |
| Question generation | `prompts/storm-questions.md` | Each persona asks questions |
| Research prompt | `prompts/react-system.md` | ReACT loop per question |
| Outline synthesis | `prompts/storm-outline.md` | Builds section structure |
| Article writing | `prompts/storm-write.md` | Writes each section |

---

## When to Use

- Long-form report generation: research reports, technical white papers, surveys.
- Topics requiring coverage from multiple domains simultaneously.
- Knowledge base articles: product documentation, internal wikis.
- Any task where breadth and depth both matter.

---

## Tradeoffs

| Pro | Con |
|---|---|
| Comprehensive multi-perspective coverage | High LLM call count and latency |
| Structured, coherent long-form output | Expensive for simple topics |
| Naturally integrates tool-based research | Requires tuning `max-steps` for deep topics |
| Diverse viewpoints reduce blind spots | Output length can be very large |

---

## Example

```yaml
intent-reactor:
  planning:
    strategy: storm
    storm:
      max-perspectives: 4
      questions-per-perspective: 3
    max-steps: 80
```

Topic: *"The impact of large language models on software development."*

Personas: Software Engineer, Tech Manager, Researcher, Ethicist.

Questions (sample):
- SE: *"Which development tasks benefit most from LLM assistance?"*
- Manager: *"What are the productivity metrics from LLM tool adoption?"*
- Researcher: *"What are the current capability limits?"*
- Ethicist: *"What are the intellectual property and attribution concerns?"*

Output: A structured article with sections on Productivity Impact, Current Capabilities, Limitations, Ethical Considerations, and Future Outlook.
