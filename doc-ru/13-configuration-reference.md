# Справочник конфигурации

Полный аннотированный YAML для всех настроек IntentReactor. Показаны значения по умолчанию; любое свойство можно не указывать, чтобы использовать дефолт.

---

```yaml
intent-reactor:

  # ─── LLM / ChatClient ───────────────────────────────────────────────────────
  llm:
    # Имя бина ChatClient. По умолчанию: единственный бин ChatClient в контексте;
    # исключение при нескольких бинах без этого свойства.
    chat-client-bean-name: ""

  # ─── Планирование ────────────────────────────────────────────────────────────
  planning:
    # Стратегия планирования. Полный список: docs/strategies/
    # Значения: react | reflexion | lats | cot | zero-shot-cot | step-back |
    #           reflection | self-ask | least-to-most | plan-and-solve |
    #           tot | got | self-discover | storm
    strategy: react

    # Максимальное количество итераций Planner.plan() до выдачи шага FAIL.
    max-steps: 50

    # Выполнять ли рискованные инструменты без подтверждения пользователя.
    autonomous: false

    # ISO-8601 длительность. Время хранения сессии в состоянии AWAITING_CONFIRMATION.
    confirmation-timeout: PT30M

    # Таймаут параллельного выполнения мультинамерений (ISO-8601).
    parallel-timeout: PT5M

    # ─── Диспетчеризация мультинамерений ──────────────────────────────────────
    multi-intent:
      # Стратегия при обнаружении > 1 намерения.
      # Значения: sequential | parallel | llm-driven
      strategy: sequential

    # ─── Настройки LATS ───────────────────────────────────────────────────────
    lats:
      num-candidates: 3           # количество параллельных веток на шаг
      min-value-threshold: 0.6    # минимальная оценка для принятия ветки (0.0–1.0)
      tree-depth: 5               # глубина дерева поиска

    # ─── Настройки Reflexion ──────────────────────────────────────────────────
    reflexion:
      max-reflections: 3          # количество циклов рефлексии

    # ─── Tree of Thoughts (ToT) ───────────────────────────────────────────────
    tot:
      num-thoughts: 3             # ветвление на шаг
      max-depth: 5                # ограничение глубины дерева
      beam-width: 2               # сохраняемые ветки (beam search)

    # ─── Graph of Thoughts (GoT) ──────────────────────────────────────────────
    got:
      num-thoughts: 3
      max-iterations: 5

    # ─── STORM ────────────────────────────────────────────────────────────────
    storm:
      max-perspectives: 5              # количество генерируемых экспертных перспектив
      questions-per-perspective: 3

    # ─── Контекстное окно ─────────────────────────────────────────────────────
    context-window:
      # Хранить не более N сообщений в промпте LLM. 0 = без ограничений.
      max-messages: 20

      # Обрезать отдельные сообщения длиннее N символов. 0 = без ограничений.
      max-message-chars: 8000

      # Добавляется к обрезанным сообщениям.
      truncation-suffix: "... [обрезано]"

      # ─── LLM-компрессия ───────────────────────────────────────────────────
      compression:
        # Отключена по умолчанию; каждое сжатие — дополнительный вызов LLM.
        enabled: false

        # Оценочный бюджет токенов для всего диалога.
        max-tokens: 4000

        # Для оценки токенов: токены ≈ символы / chars-per-token.
        chars-per-token: 4

        # Сжать при estimatedTokens > max-tokens * trigger-ratio.
        trigger-ratio: 0.85

        # Путь к шаблону промпта компрессии (classpath или file: URI).
        summary-prompt: classpath:prompts/context-compression-ru.md

  # ─── Хранилище сессий ────────────────────────────────────────────────────────
  session:
    # Бэкенд хранения сессий.
    # Значения: in-memory | filesystem | jdbc | jpa
    store: in-memory

    # ─── Filesystem ───────────────────────────────────────────────────────────
    filesystem:
      path: ./sessions

    # ─── JDBC ─────────────────────────────────────────────────────────────────
    jdbc:
      table-name: intent_reactor_sessions

  # ─── Инструменты Tool Commons ────────────────────────────────────────────────
  tools:
    # Переключатели включения/отключения отдельных инструментов.
    read-file:
      enabled: true
    write-file:
      enabled: true
    edit-file:
      enabled: true
    glob:
      enabled: true
    grep:
      enabled: true
    calculator:
      enabled: true
    datetime:
      enabled: true
    web-fetch:
      enabled: true
      max-response-kb: 200    # максимальный размер тела ответа в КБ (0 = без ограничений)
    ask-user:
      enabled: true
    file-content-extractor:
      enabled: true
    apply-patch:
      enabled: true
    todo-write-tool:
      enabled: true
    markdown-file-scanner-tool:
      enabled: true

    # ─── Динамические JavaScript-инструменты ──────────────────────────────────
    dynamic-scripting:
      enabled: false
      max-execution-time: PT5S           # таймаут выполнения скрипта
      script-repository: in-memory       # in-memory | jdbc
      max-generation-retries: 3          # повторы LLM при синтаксических ошибках
      allowed-classes: []                # дополнительные Java-классы в скриптах

  # ─── RAG ─────────────────────────────────────────────────────────────────────
  rag:
    enabled: true      # false = полностью отключить модуль (убрать knowledge_search)
    max-results: 5     # лимит результатов по умолчанию для knowledge_search

    # ─── Filesystem-источник знаний ───────────────────────────────────────────
    filesystem:
      enabled: false
      path: ./knowledge
      glob: "**/*.{txt,md}"
      max-file-size-kb: 100    # 0 = без ограничений

    # ─── JDBC-источник знаний ─────────────────────────────────────────────────
    jdbc:
      enabled: false
      table: knowledge_documents
      content-column: content
      id-column: id
      metadata-columns: []

  # ─── MCP ─────────────────────────────────────────────────────────────────────
  mcp:
    # ─── MCP-клиент ───────────────────────────────────────────────────────────
    client:
      enabled: false
      treat-mcp-tools-as-risky: false    # пометить все MCP-инструменты как рискованные
      risky-tool-names: []               # конкретные инструменты — всегда рискованные

      servers:
        - name: ""                       # уникальное имя (используется как префикс)
          transport: SSE                 # SSE | STDIO
          url: ""                        # только SSE: базовый URL сервера
          sse-path: /sse                 # только SSE: путь SSE-эндпоинта
          connect-timeout: PT10S         # только SSE
          read-timeout: PT60S            # только SSE
          command: ""                    # только STDIO: исполняемый файл
          args: []                       # только STDIO: аргументы
          env: {}                        # только STDIO: переменные окружения
          prefix-tool-names: true        # добавлять имя сервера к именам инструментов
          tool-name-prefix: null         # переопределить префикс (null = имя сервера)

    # ─── MCP-сервер ───────────────────────────────────────────────────────────
    server:
      enabled: true
      expose-tools: true       # публиковать все бины Tool как MCP-инструменты
      expose-planner: false    # публиковать intent_reactor_process / proceed / session
      server-name: intent-reactor
      server-version: 1.0.0

  # ─── Логирование ─────────────────────────────────────────────────────────────
  logging:
    # Отключите встроенный IntentReactorEventLogger при наличии кастомных слушателей.
    enabled: true
```

---

## Быстрый указатель свойств

| Свойство | Тип | По умолчанию |
|---|---|---|
| `planning.strategy` | String | `react` |
| `planning.max-steps` | int | `50` |
| `planning.autonomous` | boolean | `false` |
| `planning.confirmation-timeout` | Duration | `PT30M` |
| `planning.parallel-timeout` | Duration | `PT5M` |
| `planning.multi-intent.strategy` | String | `sequential` |
| `planning.lats.num-candidates` | int | `3` |
| `planning.lats.min-value-threshold` | double | `0.6` |
| `planning.lats.tree-depth` | int | `5` |
| `planning.reflexion.max-reflections` | int | `3` |
| `planning.tot.num-thoughts` | int | `3` |
| `planning.tot.max-depth` | int | `5` |
| `planning.tot.beam-width` | int | `2` |
| `planning.got.num-thoughts` | int | `3` |
| `planning.got.max-iterations` | int | `5` |
| `planning.storm.max-perspectives` | int | `5` |
| `planning.storm.questions-per-perspective` | int | `3` |
| `planning.context-window.max-messages` | int | `20` |
| `planning.context-window.max-message-chars` | int | `8000` |
| `planning.context-window.compression.enabled` | boolean | `false` |
| `planning.context-window.compression.max-tokens` | int | `4000` |
| `planning.context-window.compression.trigger-ratio` | double | `0.85` |
| `session.store` | String | `in-memory` |
| `session.filesystem.path` | String | `./sessions` |
| `session.jdbc.table-name` | String | `intent_reactor_sessions` |
| `tools.dynamic-scripting.enabled` | boolean | `false` |
| `tools.dynamic-scripting.max-execution-time` | Duration | `PT5S` |
| `tools.dynamic-scripting.script-repository` | String | `in-memory` |
| `rag.enabled` | boolean | `true` |
| `rag.max-results` | int | `5` |
| `mcp.client.enabled` | boolean | `false` |
| `mcp.server.enabled` | boolean | `true` |
| `mcp.server.expose-tools` | boolean | `true` |
| `mcp.server.expose-planner` | boolean | `false` |
| `logging.enabled` | boolean | `true` |
