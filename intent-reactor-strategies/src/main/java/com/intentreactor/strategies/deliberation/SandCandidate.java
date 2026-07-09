package com.intentreactor.strategies.deliberation;

import java.util.Map;

/**
 * Represents one action candidate during a SAND deliberation cycle.
 * <p>
 * Candidate at index 0 is always the delegate planner's original proposal.
 * Candidates at indices 1..N-1 are LLM-generated alternatives.
 */
public class SandCandidate {

    private int index;
    private String toolName;
    private Map<String, Object> parameters;
    private String reasoning;
    private String prediction;
    private boolean fromSimulation;
    private double score = 0.5;

    public SandCandidate(int index, String toolName, Map<String, Object> parameters, String reasoning) {
        this.index = index;
        this.toolName = toolName;
        this.parameters = parameters;
        this.reasoning = reasoning;
    }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getParameters() { return parameters; }
    public String getReasoning() { return reasoning; }
    public String getPrediction() { return prediction; }
    public void setPrediction(String prediction) { this.prediction = prediction; }
    public boolean isFromSimulation() { return fromSimulation; }
    public void setFromSimulation(boolean fromSimulation) { this.fromSimulation = fromSimulation; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}
