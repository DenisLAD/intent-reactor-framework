# Configuration Reference

Complete annotated YAML for all IntentReactor properties. Defaults are shown; omit any property to use the default.

---

```yaml
intent-reactor:

  # ─── LLM / ChatClient ───────────────────────────────────────────────────────
  llm:
    # Spring AI chat client bean name to use. Default: picks the only ChatClient
    # bean, or throws if multiple are present and this property is not set.
    chat-client-bean-name: ""

  # ─── Planning ────────────────────────────────────────────────────────────────
  planning:
    # Planning strategy value. See docs/strategies/ for details on each.
    # Values: react | reflexion | lats | cot | zero-shot-cot | step-back |
    #         reflection | self-ask | least-to-most | plan-and-solve |
    #         tot | got | self-discover | storm |
    #         retreval | map | htp | knowagent
    strategy: react

    # Maximum number of Planner.plan() iterations before a FAIL step is emitted.
    max-steps: 50

    # Whether the planner can run risky tools without user confirmation.
    # When true, risky tools execute automatically; ConfirmationRequiredEvent
    # is still published but proceedAfterConfirmation() is not required.
    autonomous: false

    # ISO-8601 duration. How long a session may stay in AWAITING_CONFIRMATION
    # state before it is automatically marked as timed out.
    confirmation-timeout: PT30M

    # Timeout for parallel multi-intent execution (ISO-8601 duration).
    parallel-timeout: PT5M

    # ─── Multi-intent dispatch ─────────────────────────────────────────────────
    multi-intent:
      # Strategy used when the preprocessor detects > 1 intent.
      # Values: sequential | parallel | llm-driven
      strategy: sequential

    # ─── LATS-specific settings ───────────────────────────────────────────────
    lats:
      # Number of candidate trajectories explored per planning step.
      num-candidates: 3

      # Minimum value score (0.0–1.0) to accept a trajectory.
      min-value-threshold: 0.6

      # Depth limit of the search tree.
      tree-depth: 5

    # ─── Reflexion-specific settings ──────────────────────────────────────────
    reflexion:
      # Number of reflection cycles before falling back to plain ReACT.
      max-reflections: 3

    # ─── Tree of Thoughts (ToT) ───────────────────────────────────────────────
    tot:
      num-thoughts: 3           # branching factor per step
      max-depth: 5              # tree depth limit
      beam-width: 2             # kept branches (beam search)

    # ─── Graph of Thoughts (GoT) ──────────────────────────────────────────────
    got:
      num-thoughts: 3
      max-iterations: 5

    # ─── STORM ────────────────────────────────────────────────────────────────
    storm:
      max-perspectives: 5       # number of expert perspectives generated
      questions-per-perspective: 3

    # ─── Strategies-module fine-tuning (intent-reactor-strategies) ───────────
    # These properties are under StrategiesProperties and only apply when the
    # corresponding strategy is active.
    strategies:
      reflection:
        max-iterations: 3
        satisfaction-threshold: 0.8
      tot:
        search-algorithm: bfs      # bfs | dfs | beam
        beam-width: 3
        thoughts-per-step: 3
        max-depth: 5
      got:
        max-operations: 10
        aggregation-threshold: 0.7
      self-discover:
        module-count: 5
      storm:
        perspective-count: 3
        max-research-steps: 5
      self-ask:
        max-sub-questions: 5
      least-to-most:
        max-subproblems: 5
      plan-and-solve:
        max-plan-steps: 8
      retreval:
        max-tree-depth: 4          # maximum depth of the reasoning tree
        candidates-per-step: 3    # candidate nodes generated per EXPAND phase
        validation-threshold: 0.6 # minimum critic score to accept a node
      map:
        max-subtasks: 5
        progress-threshold: 0.8   # Evaluator score above which goal is considered done
        use-conflict-monitor: true # activate ConflictMonitor module on tool errors
        use-state-predictor: false # activate optional StatePredictor module
        eval-interval-steps: 3    # run Evaluator every N steps
      htp:
        max-subgoals: 4            # maximum number of decomposed subgoals
        max-steps-per-node: 5     # maximum plan steps per subgoal
        refinement-enabled: true  # run REFINE phase after each subgoal
        max-refinement-retries: 2 # retries before marking subgoal as FAILED
      know-agent:
        enrich-knowledge: false    # true = enrich KB with an LLM call (extra latency)
        filter-by-preconditions: true # filter tools whose preconditions are unmet

    # ─── Context window ───────────────────────────────────────────────────────
    context-window:
      # Keep at most this many messages in the LLM prompt.
      # 0 = unlimited.
      max-messages: 20

      # Truncate individual messages longer than this many characters.
      # 0 = unlimited.
      max-message-chars: 8000

      # Appended to messages that were truncated.
      truncation-suffix: "... [truncated]"

      # ─── LLM-based compression ────────────────────────────────────────────
      compression:
        # Disabled by default; each compression incurs an extra LLM call.
        enabled: false

        # Estimated token budget for the entire conversation.
        max-tokens: 4000

        # Used to estimate token count: tokens ≈ chars / chars-per-token.
        chars-per-token: 4

        # Compress when estimatedTokens > max-tokens * trigger-ratio.
        trigger-ratio: 0.85

        # Path to the compression prompt template (classpath or file: URI).
        summary-prompt: classpath:prompts/context-compression-ru.md

  # ─── Session store ───────────────────────────────────────────────────────────
  session:
    # Session persistence backend.
    # Values: in-memory | filesystem | jdbc | jpa
    store: in-memory

    # ─── Filesystem store ─────────────────────────────────────────────────────
    filesystem:
      # Directory where JSON session files are stored.
      path: ./sessions

    # ─── JDBC store ───────────────────────────────────────────────────────────
    jdbc:
      # Table name for session records.
      table-name: intent_reactor_sessions

  # ─── Tool Commons ────────────────────────────────────────────────────────────
  tools:
    # Individual tool enable/disable switches.
    # By default all available Tool beans on the classpath are enabled.
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
      # Maximum response body size in KB (0 = unlimited).
      max-response-kb: 200
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

    # ─── Dynamic JavaScript tools ─────────────────────────────────────────────
    dynamic-scripting:
      enabled: false

      # Maximum wall-clock time a script may run before being killed.
      max-execution-time: PT5S

      # Repository backend for ScriptDefinition records.
      # Values: in-memory | jdbc
      script-repository: in-memory

      # How many times to retry LLM code generation on syntax errors.
      max-generation-retries: 3

      # Additional Java classes accessible from inside scripts.
      # The default whitelist (Math, String, collections, etc.) always applies.
      allowed-classes: []

  # ─── RAG ─────────────────────────────────────────────────────────────────────
  rag:
    # Set to false to disable the entire RAG module (removes knowledge_search).
    enabled: true

    # Default max_results value for the knowledge_search tool.
    max-results: 5

    # ─── Filesystem knowledge source ─────────────────────────────────────────
    filesystem:
      enabled: false
      path: ./knowledge
      glob: "**/*.{txt,md}"
      # Skip files larger than this many KB (0 = no limit).
      max-file-size-kb: 100

    # ─── JDBC knowledge source ────────────────────────────────────────────────
    jdbc:
      enabled: false
      table: knowledge_documents
      content-column: content
      id-column: id
      metadata-columns: []

  # ─── MCP ─────────────────────────────────────────────────────────────────────
  mcp:
    # ─── MCP Client ───────────────────────────────────────────────────────────
    client:
      enabled: false

      # Mark ALL tools from ALL MCP servers as risky (require confirmation).
      treat-mcp-tools-as-risky: false

      # Specific MCP tool names always treated as risky regardless of above.
      risky-tool-names: []

      servers:
          # Unique name for this server (used as tool name prefix).
        - name: ""

          # Transport type. Values: SSE | STDIO
          transport: SSE

          # SSE only: base URL of the MCP server.
          url: ""

          # SSE only: SSE endpoint path.
          sse-path: /sse

          # SSE only: connection timeout.
          connect-timeout: PT10S

          # SSE only: read / idle timeout.
          read-timeout: PT60S

          # STDIO only: executable to launch.
          command: ""

          # STDIO only: arguments for the executable.
          args: []

          # STDIO only: environment variables passed to the process.
          env: {}

          # Prepend server name to all tool names (prevents collisions).
          prefix-tool-names: true

          # Override the prefix string (null = use server name).
          tool-name-prefix: null

    # ─── MCP Server ───────────────────────────────────────────────────────────
    server:
      enabled: true

      # Publish all Tool beans as MCP tools.
      expose-tools: true

      # Publish intent_reactor_process / proceed / session MCP tools.
      expose-planner: false

      # MCP server identity fields.
      server-name: intent-reactor
      server-version: 1.0.0

  # ─── Logging ─────────────────────────────────────────────────────────────────
  logging:
    # Disable the built-in IntentReactorEventLogger if you have custom listeners.
    enabled: true
```

---

## Quick property index

| Property | Type | Default |
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
| `planning.strategies.retreval.max-tree-depth` | int | `4` |
| `planning.strategies.retreval.candidates-per-step` | int | `3` |
| `planning.strategies.retreval.validation-threshold` | double | `0.6` |
| `planning.strategies.map.max-subtasks` | int | `5` |
| `planning.strategies.map.progress-threshold` | double | `0.8` |
| `planning.strategies.map.use-conflict-monitor` | boolean | `true` |
| `planning.strategies.map.eval-interval-steps` | int | `3` |
| `planning.strategies.htp.max-subgoals` | int | `4` |
| `planning.strategies.htp.max-steps-per-node` | int | `5` |
| `planning.strategies.htp.refinement-enabled` | boolean | `true` |
| `planning.strategies.htp.max-refinement-retries` | int | `2` |
| `planning.strategies.know-agent.enrich-knowledge` | boolean | `false` |
| `planning.strategies.know-agent.filter-by-preconditions` | boolean | `true` |
| `mcp.client.enabled` | boolean | `false` |
| `mcp.server.enabled` | boolean | `true` |
| `mcp.server.expose-tools` | boolean | `true` |
| `mcp.server.expose-planner` | boolean | `false` |
| `logging.enabled` | boolean | `true` |
