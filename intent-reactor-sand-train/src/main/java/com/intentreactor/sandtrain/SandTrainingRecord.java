package com.intentreactor.sandtrain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class SandTrainingRecord {

    private String sessionId;
    private int stepIndex;
    private String goal;
    private String contextSummary;
    private List<Map<String, Object>> candidates;
    private int selectedIndex;
    private LocalDateTime timestamp;

    public SandTrainingRecord() {}

    public SandTrainingRecord(String sessionId, int stepIndex, String goal, String contextSummary,
                               List<Map<String, Object>> candidates, int selectedIndex) {
        this.sessionId = sessionId;
        this.stepIndex = stepIndex;
        this.goal = goal;
        this.contextSummary = contextSummary;
        this.candidates = candidates;
        this.selectedIndex = selectedIndex;
        this.timestamp = LocalDateTime.now();
    }

    public String getSessionId() { return sessionId; }
    public int getStepIndex() { return stepIndex; }
    public String getGoal() { return goal; }
    public String getContextSummary() { return contextSummary; }
    public List<Map<String, Object>> getCandidates() { return candidates; }
    public int getSelectedIndex() { return selectedIndex; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
