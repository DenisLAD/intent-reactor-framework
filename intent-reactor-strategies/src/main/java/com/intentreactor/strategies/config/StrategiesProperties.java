package com.intentreactor.strategies.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "intent-reactor.planning.strategies")
public class StrategiesProperties {

    private PromptsConfig prompts = new PromptsConfig();
    private LabelsConfig labels = new LabelsConfig();

    private ReflectionConfig reflection = new ReflectionConfig();
    private TotConfig tot = new TotConfig();
    private GotConfig got = new GotConfig();
    private SelfDiscoverConfig selfDiscover = new SelfDiscoverConfig();
    private StormConfig storm = new StormConfig();
    private SelfAskConfig selfAsk = new SelfAskConfig();
    private LeastToMostConfig leastToMost = new LeastToMostConfig();
    private PlanAndSolveConfig planAndSolve = new PlanAndSolveConfig();
    private RetrevalConfig retreval = new RetrevalConfig();
    private MapConfig map = new MapConfig();
    private HtpConfig htp = new HtpConfig();
    private KnowAgentConfig knowAgent = new KnowAgentConfig();

    @Data
    public static class PromptsConfig {
        // CoT
        private String cotSystem             = "classpath:prompts/strategies/cot-system.md";
        private String zeroShotCotSystem     = "classpath:prompts/strategies/zero-shot-cot-system.md";
        // StepBack
        private String stepBackAbstract      = "classpath:prompts/strategies/step-back-abstract.md";
        private String stepBackUser          = "classpath:prompts/strategies/step-back-user.md";
        // Reflection
        private String reflectionUser        = "classpath:prompts/strategies/reflection-user.md";
        // SelfAsk
        private String selfAskDecompose      = "classpath:prompts/strategies/self-ask-decompose.md";
        private String selfAskSynthesize     = "classpath:prompts/strategies/self-ask-synthesize.md";
        // LeastToMost
        private String leastToMostDecompose  = "classpath:prompts/strategies/least-to-most-decompose.md";
        private String leastToMostSolve      = "classpath:prompts/strategies/least-to-most-solve.md";
        private String leastToMostSynthesize = "classpath:prompts/strategies/least-to-most-synthesize.md";
        // PlanAndSolve
        private String planAndSolvePlan      = "classpath:prompts/strategies/plan-and-solve-plan.md";
        // ToT
        private String totGenerate           = "classpath:prompts/strategies/tot-generate.md";
        private String totEvaluate           = "classpath:prompts/strategies/tot-evaluate.md";
        // GoT
        private String gotGenerate           = "classpath:prompts/strategies/got-generate.md";
        private String gotScore              = "classpath:prompts/strategies/got-score.md";
        // ReTreVal
        private String retrevalExpand        = "classpath:prompts/strategies/retreval-expand.md";
        private String retrevalSelfScore     = "classpath:prompts/strategies/retreval-self-score.md";
        private String retrevalCriticScore   = "classpath:prompts/strategies/retreval-critic-score.md";
        private String retrevalSynthesize    = "classpath:prompts/strategies/retreval-synthesize.md";
        private String retrevalSimulate      = "classpath:prompts/strategies/retreval-simulate.md";
        private String retrevalBacktrack     = "classpath:prompts/strategies/retreval-backtrack.md";
        // SelfDiscover
        private String selfDiscoverSelect    = "classpath:prompts/strategies/self-discover-select.md";
        private String selfDiscoverAdapt     = "classpath:prompts/strategies/self-discover-adapt.md";
        // Storm
        private String stormPerspectives     = "classpath:prompts/strategies/storm-perspectives.md";
        private String stormResearch         = "classpath:prompts/strategies/storm-research.md";
        private String stormSynthesize       = "classpath:prompts/strategies/storm-synthesize.md";
        // MAP
        private String mapDecompose          = "classpath:prompts/strategies/map-decompose.md";
        private String mapEvaluate           = "classpath:prompts/strategies/map-evaluate.md";
        private String mapConflict           = "classpath:prompts/strategies/map-conflict.md";
        private String mapPredict            = "classpath:prompts/strategies/map-predict.md";
        private String mapExecuteSystem      = "classpath:prompts/strategies/map-execute-system.md";
        // HTP
        private String htpDecompose          = "classpath:prompts/strategies/htp-decompose.md";
        private String htpPlanNode           = "classpath:prompts/strategies/htp-plan-node.md";
        private String htpRefine             = "classpath:prompts/strategies/htp-refine.md";
        private String htpSynthesize         = "classpath:prompts/strategies/htp-synthesize.md";
        // KnowAgent
        private String knowagentEnrich       = "classpath:prompts/strategies/knowagent-enrich.md";
    }

    @Data
    public static class LabelsConfig {
        private String task                = "Task: ";
        private String topic               = "Topic: ";
        private String backgroundKnowledge = "[BACKGROUND KNOWLEDGE]\n";
        private String critique            = "[CRITIQUE] Score: ";
        private String weaknesses          = "\nWeaknesses: ";
        private String improvement         = "\nImprovement: ";
        private String originalQuestion    = "Original question: ";
        private String collectedAnswers    = "\n\nCollected answers:\n";
        private String giveFinalAnswer     = "\n\nNow give the final answer to the original question.";
        private String originalTask        = "Original task: ";
        private String previousResults     = "\n\nPrevious subtask results:\n";
        private String subtaskResults      = "\n\nSubtask results:\n";
        private String currentReasoning    = "\n\nCurrent reasoning: ";
        private String thoughtToEvaluate   = "\n\nThought to evaluate: ";
        private String currentGraph        = "\n\nCurrent thought graph:\n";
        private String chooseNextOperation = "\n\nChoose the next operation.";
        private String selectedModules     = "Selected modules:\n";
        private String structuredPlan      = "[STRUCTURED REASONING PLAN]\n";
        private String expertResearch      = "\n\nExpert research:\n";
        private String conductResearch     = "Conduct research from your role's perspective.";
        private String availableTools      = "\n\nAvailable tools:\n";
        private String tools               = "Available tools:\n";
        private String subQuestion         = "[SUB-QUESTION ";
        private String subtask             = "[SUBTASK ";
        private String noSolution          = "No solution found.";
        private String continueExpansion   = "Continuing exploration...";
        // KnowAgent
        private String kbAvailable         = "Available tools (preconditions met): ";
        private String kbUnavailable       = "Unavailable tools: ";
        private String kbBlocked           = " [BLOCKED: ";
        private String kbContraindications = "Contraindications:\n";
        private String kbWarning           = "WARNING: ";
        private String enrichUserPrefix    = "Tools:\n";
        // MAP
        private String mapGoalPrefix         = "Goal:";
        private String mapProposedPlan        = "Proposed plan:";
        private String mapSubgoalsHeader      = "Subgoals:";
        private String mapSubgoalLabel        = "Subgoal:";
        private String mapDependsOn           = " [depends on: ";
        private String mapIndependent         = " [independent]";
        private String mapResolveConflicts    = "Resolve the following conflicts in the new plan:";
        private String mapStatePredictionTag  = "[MAP:StatePredictor] Prediction — ";
        private String mapPlanHeader          = "[MAP PLAN]\nGoal:";
        private String mapToolParameters      = "  Parameters: ";
        private String mapToolRequired        = " (required: ";
    }

    @Data
    public static class ReflectionConfig {
        private int maxIterations = 3;
        private double satisfactionThreshold = 0.8;
    }

    @Data
    public static class TotConfig {
        /** bfs | dfs | beam */
        private String searchAlgorithm = "bfs";
        private int beamWidth = 3;
        private int thoughtsPerStep = 3;
        private int maxDepth = 5;
    }

    @Data
    public static class GotConfig {
        private int maxOperations = 10;
        private double aggregationThreshold = 0.7;
    }

    @Data
    public static class SelfDiscoverConfig {
        private int moduleCount = 5;
    }

    @Data
    public static class StormConfig {
        private int perspectiveCount = 3;
        private int maxResearchSteps = 5;
    }

    @Data
    public static class SelfAskConfig {
        private int maxSubQuestions = 5;
    }

    @Data
    public static class LeastToMostConfig {
        private int maxSubproblems = 5;
    }

    @Data
    public static class PlanAndSolveConfig {
        private int maxPlanSteps = 8;
    }

    @Data
    public static class RetrevalConfig {
        private int maxTreeDepth = 4;
        private int candidatesPerStep = 3;
        private double validationThreshold = 0.6;
        private int beamWidth = 2;
        private double finalThreshold = 0.75;
        private boolean useExternalCritic = true;
        private boolean memoryEnabled = true;
        private int maxMemories = 10;
    }

    @Data
    public static class MapConfig {
        private int maxSubtasks = 5;
        private int maxPlanningIterations = 3;
        private double confidenceThreshold = 0.7;
        private boolean useConflictMonitor = true;
        private boolean useStatePredictor = false;
    }

    @Data
    public static class HtpConfig {
        private int maxSubgoals = 4;
        private int maxStepsPerNode = 5;
        private boolean refinementEnabled = true;
        private int maxRefinementRetries = 2;
    }

    @Data
    public static class KnowAgentConfig {
        private boolean enrichKnowledge = false;
        private boolean filterByPreconditions = true;
    }
}
