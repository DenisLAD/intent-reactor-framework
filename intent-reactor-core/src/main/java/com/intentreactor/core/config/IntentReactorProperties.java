package com.intentreactor.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "intent-reactor")
public class IntentReactorProperties {

    private LlmConfig llm = new LlmConfig();
    private PlanningConfig planning = new PlanningConfig();
    private SessionConfig session = new SessionConfig();
    private ToolsConfig tools = new ToolsConfig();
    private IntentPreprocessorConfig intent = new IntentPreprocessorConfig();
    private LoggingConfig logging = new LoggingConfig();

    // ---- Nested config classes ----

    @Getter
    @Setter
    public static class LlmConfig {
        private String provider = "openai";
        private String model = "gpt-4o";
        private double temperature = 0.1;
        private String timeout = "30s";
        private PromptResources promptResources = new PromptResources();
    }

    @Getter
    @Setter
    public static class PromptResources {
        private String system = "classpath:prompts/default-system-ru.md";
        private String intent = "classpath:prompts/default-intent-ru.md";
        private String formatCorrection = "classpath:prompts/react-format-correction-ru.md";
        private String reflexionSystem = "classpath:prompts/reflexion-system-ru.md";
        private String reflexionUser = "classpath:prompts/reflexion-user-ru.md";
        private String intentRetry = "classpath:prompts/intent-retry-ru.md";
        private String latsActionsSystem = "classpath:prompts/lats-actions-system-ru.md";
        private String latsActionsUser = "classpath:prompts/lats-actions-user-ru.md";
        private String latsEvaluateSystem = "classpath:prompts/lats-evaluate-system-ru.md";
        private String latsEvaluateUser = "classpath:prompts/lats-evaluate-user-ru.md";
        private String llmDrivenOrdering = "classpath:prompts/llm-driven-ordering-ru.md";
    }

    @Getter
    @Setter
    public static class PlanningConfig {
        private String strategy = "react";
        private boolean autonomous = false;
        private int maxSteps = 10;
        private int maxRetries = 3;
        private MultiIntentConfig multiIntent = new MultiIntentConfig();
        private ReflexionConfig reflexion = new ReflexionConfig();
        private LatsConfig lats = new LatsConfig();
        private ContextWindowConfig contextWindow = new ContextWindowConfig();
        private Duration confirmationTimeout = Duration.ofMinutes(30);
        private Duration parallelTimeout = Duration.ofSeconds(60);
    }

    @Getter
    @Setter
    public static class ContextWindowConfig {
        private int maxMessages = 20;
        private int maxMessageChars = 8000;
        private int maxSnapshotChars = 30000;
        private String truncationSuffix = "... [truncated]";
        private CompressionConfig compression = new CompressionConfig();
    }

    @Getter
    @Setter
    public static class CompressionConfig {
        private boolean enabled = false;
        private int maxTokens = 4000;
        private int charsPerToken = 4;
        private double triggerRatio = 0.85;
        private String summaryPrompt = "classpath:prompts/context-compression-ru.md";
    }

    @Getter
    @Setter
    public static class MultiIntentConfig {
        private String strategy = "sequential";
    }

    @Getter
    @Setter
    public static class ReflexionConfig {
        private int maxReflectionSteps = 3;
    }

    @Getter
    @Setter
    public static class LatsConfig {
        private int maxIterations = 50;
        private double explorationConstant = 1.4;
        private int branchingFactor = 3;
        private int simulationDepth = 1;
        private boolean allowRealActionsInSimulation = false;
    }

    @Getter
    @Setter
    public static class SessionConfig {
        private String store = "in-memory";
        private JdbcSessionConfig jdbc = new JdbcSessionConfig();
        private FileSystemSessionConfig filesystem = new FileSystemSessionConfig();
    }

    @Getter
    @Setter
    public static class JdbcSessionConfig {
        private String tableName = "intent_reactor_sessions";
    }

    @Getter
    @Setter
    public static class FileSystemSessionConfig {
        private String path = "./sessions";
    }

    @Getter
    @Setter
    public static class ToolsConfig {
        private String scanPackages = "";
    }

    @Getter
    @Setter
    public static class IntentPreprocessorConfig {
        private PreprocessorConfig preprocessor = new PreprocessorConfig();
    }

    @Getter
    @Setter
    public static class PreprocessorConfig {
        private int maxHistory = 10;
    }

    @Getter
    @Setter
    public static class LoggingConfig {
        private boolean enabled = true;
    }
}
