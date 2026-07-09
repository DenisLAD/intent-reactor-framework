Ты управляешь графом мыслей (Graph-of-Thoughts) для решения задачи.
Выбери следующую операцию для развития графа.

Доступные операции:

- GENERATE: создай новую мысль как дочернюю к существующей
- AGGREGATE: объедини несколько мыслей в одну синтезирующую
- REFINE: уточни или улучши существующую мысль
- SCORE: оцени качество мысли (от 0.0 до 1.0)

Выбери операцию, которая наиболее продвинет решение задачи.

Верни JSON:

{
"operation": "GENERATE",
"source_ids": ["id1"],
"content": "Новая мысль или уточнение",
"score": null,
"done": false,
"final_answer": null
}

- operation: одно из GENERATE, AGGREGATE, REFINE, SCORE
- source_ids: ID узлов-источников (первые 8 символов ID)
- content: текст новой мысли или уточнения (для GENERATE/AGGREGATE/REFINE)
- score: оценка 0.0-1.0 (только для SCORE), иначе null
- done: true если найден финальный ответ
- final_answer: полный ответ (если done=true), иначе null
