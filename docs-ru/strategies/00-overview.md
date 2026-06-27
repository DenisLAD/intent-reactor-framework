# Стратегии планирования — обзор

IntentReactor поставляется с 18 стратегиями планирования — от простого цепного рассуждения до многотраекторного поиска по дереву. Стратегия выбирается при запуске через одно свойство.

---

## Выбор стратегии

```yaml
intent-reactor:
  planning:
    strategy: react   # изменить это значение
```

Все 18 стратегий работают через один и тот же API `IntentReactorService.process()`. Смена стратегии не требует изменений в коде — только запись в `application.yml` и, для не-core стратегий, соответствующий Maven-модуль.

---

## Каталог стратегий

| Значение | Название | Категория | Модуль | Лучше всего подходит |
|---|---|---|---|---|
| `react` | ReACT | Итеративное использование инструментов | core | Большинство задач — универсальный выбор |
| `reflexion` | Reflexion | Итеративная + самокритика | core | Задачи, где первая попытка часто неудачна |
| `lats` | LATS | Поиск по дереву + симуляция | core | Сложные задачи, требующие исследования |
| `cot` | Chain-of-Thought | Одиночное рассуждение | strategies | Аналитика без инструментов |
| `zero-shot-cot` | Zero-shot CoT | Одиночное рассуждение | strategies | Быстрый анализ без примеров |
| `step-back` | Step-Back | Обобщение | strategies | Предметные вопросы, экспертные знания |
| `reflection` | Reflection | Одиночная самокритика | strategies | Улучшение качества ответа после генерации |
| `self-ask` | Self-Ask | Декомпозиция | strategies | Многоходовые фактологические вопросы |
| `least-to-most` | Least-to-Most | Прогрессивная декомпозиция | strategies | Образовательные / составные задачи |
| `plan-and-solve` | Plan-and-Solve | Двухфазное планирование | strategies | Структурированные многошаговые планы |
| `tot` | Tree of Thoughts | Параллельное исследование | strategies | Творческие задачи, открытые рассуждения |
| `got` | Graph of Thoughts | Агрегация графа | strategies | Суммаризация, слияние, ранжирование |
| `self-discover` | Self-Discover | Метакогниция | strategies | Новые задачи без очевидной структуры |
| `storm` | STORM | Синтез экспертных перспектив | strategies | Подробные отчёты, исчерпывающие темы |
| `retreval` | ReTreVal | Поиск по дереву + валидация | strategies | Сложные многошаговые задачи с бэктрекингом |
| `map` | MAP (Modular Agentic Planner) | Мультимодульная оркестрация | strategies | Задачи с необходимостью контроля прогресса |
| `htp` | HTP (HyperTree Planning) | Иерархическая декомпозиция | strategies | Цели с чёткой структурой подцелей и ограничениями |
| `knowagent` | KnowAgent | ReACT с базой знаний | strategies | Задачи со сложной логикой предусловий инструментов |

---

## Модули

**`intent-reactor-core`** — всегда включён через стартер. Предоставляет: `react`, `reflexion`, `lats`.

**`intent-reactor-strategies`** — добавить явно для остальных 15 стратегий:

```xml
<dependency>
    <groupId>com.intentreactor</groupId>
    <artifactId>intent-reactor-strategies</artifactId>
    <version>0.1.14</version>
</dependency>
```

---

## Архитектура: декораторы vs самостоятельные

**Core-стратегии** (`react`, `reflexion`, `lats`) реализуют полный интерфейс `Planner` и самостоятельно управляют циклом итераций ReACT.

**Стратегии из модуля strategies** делятся на два подтипа:
- **Декораторы над `react`** — выполняют фазу предобработки, формирующую цель, затем делегируют в базовый ReACT-цикл (`cot`, `zero-shot-cot`, `step-back`, `reflection`, `self-discover`, `knowagent`).
- **Самостоятельные планировщики** — управляют собственным циклом вызова инструментов (`self-ask`, `least-to-most`, `plan-and-solve`, `tot`, `got`, `storm`, `retreval`, `map`, `htp`).

Все 15 стратегий модуля поддерживают инструменты.

```
Запрос
   │
   ├── [планировщик из strategies] (предобработка)
   │        │
   │        └── [планировщик react] (цикл вызова инструментов)
   │
   └── [core-планировщик] (react | reflexion | lats — самодостаточный)
```

---

## Обёртка Micrometer

При наличии `io.micrometer:micrometer-core` в classpath каждый планировщик автоматически оборачивается `MicrometerPlannerDecorator` при старте. Метрики тегируются значением `strategy` (например, `strategy=lats`). Подробнее — в [Событиях и метриках](../12-events-and-metrics.md).

---

## Выбор стратегии

Дерево решений:

```
Нужен ли доступ к инструментам?
  ├── Нет → cot / zero-shot-cot / step-back / reflection
  └── Да
        ├── Простые, чётко определённые задачи → react
        ├── Задачи с пользой от самокритики → reflexion
        ├── Задачи, требующие исследования вариантов → lats / tot / got / retreval
        ├── Многоходовые фактологические вопросы → self-ask
        ├── Прогрессивная декомпозиция → least-to-most / plan-and-solve
        ├── Иерархические цели с ограничениями → htp
        ├── Задачи с контролем прогресса → map
        ├── Сложная логика предусловий инструментов → knowagent
        ├── Новые / метакогнитивные задачи → self-discover
        └── Длинные отчёты / синтез → storm
```

Документация по стратегиям: [react](01-react.md) · [reflexion](02-reflexion.md) · [lats](03-lats.md) · [cot](04-cot.md) · [zero-shot-cot](05-zero-shot-cot.md) · [step-back](06-step-back.md) · [reflection](07-reflection.md) · [self-ask](08-self-ask.md) · [least-to-most](09-least-to-most.md) · [plan-and-solve](10-plan-and-solve.md) · [tot](11-tree-of-thoughts.md) · [got](12-graph-of-thoughts.md) · [self-discover](13-self-discover.md) · [storm](14-storm.md) · [retreval](15-retreval.md) · [map](16-map.md) · [htp](17-htp.md) · [knowagent](18-knowagent.md)
