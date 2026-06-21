package com.intentreactor.strategies.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "intent-reactor.planning.strategies")
public class StrategiesProperties {

    private ReflectionConfig reflection = new ReflectionConfig();
    private TotConfig tot = new TotConfig();
    private GotConfig got = new GotConfig();
    private SelfDiscoverConfig selfDiscover = new SelfDiscoverConfig();
    private StormConfig storm = new StormConfig();
    private SelfAskConfig selfAsk = new SelfAskConfig();
    private LeastToMostConfig leastToMost = new LeastToMostConfig();
    private PlanAndSolveConfig planAndSolve = new PlanAndSolveConfig();

    @Data
    public static class ReflectionConfig {
        private int maxIterations = 3;
        private double satisfactionThreshold = 0.8;
    }

    @Data
    public static class TotConfig {
        /**
         * bfs | dfs | beam
         */
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
}
