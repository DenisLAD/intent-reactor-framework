package com.intentreactor.sandtrain;

import com.intentreactor.api.SessionStore;
import com.intentreactor.api.StepType;
import com.intentreactor.api.event.PlanStepCompletedEvent;
import com.intentreactor.strategies.config.StrategySessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects SAND deliberation logs from completed ACT steps.
 * <p>
 * Listens for {@link PlanStepCompletedEvent} and reads the training log stored in
 * {@code session.attributes["sand_training_log"]} by {@code SANDPlanner}.
 * <p>
 * NOTE: Reliable only with {@code InMemorySessionStore} — with file/JDBC stores the session
 * lookup after event may return stale data since the event fires before {@code sessionStore.save()}.
 */
public class SandDataCollector {

    private static final Logger log = LoggerFactory.getLogger(SandDataCollector.class);

    private final SessionStore sessionStore;
    private final CopyOnWriteArrayList<SandTrainingRecord> records = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Integer> processedCounts = new ConcurrentHashMap<>();

    public SandDataCollector(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @EventListener
    public void onStepCompleted(PlanStepCompletedEvent event) {
        if (event.getStep() == null || event.getStep().type() != StepType.ACT) return;
        String sessionId = event.getSessionId();
        sessionStore.findById(sessionId).ifPresent(session -> {
            Object raw = session.getAttributes().get(StrategySessionKeys.SAND_TRAINING_LOG);
            if (!(raw instanceof List<?> log_)) return;

            int processed = processedCounts.getOrDefault(sessionId, 0);
            if (log_.size() <= processed) return;

            for (int i = processed; i < log_.size(); i++) {
                Object entry = log_.get(i);
                if (!(entry instanceof Map<?, ?> entryMap)) continue;
                try {
                    SandTrainingRecord record = mapToRecord(sessionId, entryMap);
                    records.add(record);
                    log.debug("[SandTrain] Collected step {} for session {}", record.getStepIndex(), sessionId);
                } catch (Exception e) {
                    log.warn("[SandTrain] Failed to parse training entry: {}", e.getMessage());
                }
            }
            processedCounts.put(sessionId, log_.size());
        });
    }

    @SuppressWarnings("unchecked")
    private SandTrainingRecord mapToRecord(String sessionId, Map<?, ?> rawMap) {
        Map<String, Object> map = (Map<String, Object>) rawMap;
        int stepIndex = ((Number) map.getOrDefault("stepIndex", 0)).intValue();
        String goal = String.valueOf(map.getOrDefault("goal", ""));
        String ctx = String.valueOf(map.getOrDefault("contextSummary", ""));
        int selectedIndex = ((Number) map.getOrDefault("selectedIndex", 0)).intValue();
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) map.getOrDefault("candidates", List.of());
        return new SandTrainingRecord(sessionId, stepIndex, goal, ctx, candidates, selectedIndex);
    }

    public List<SandTrainingRecord> getRecords() {
        return new ArrayList<>(records);
    }

    public int getTotalRecords() {
        return records.size();
    }

    public long getSessionCount() {
        return records.stream().map(SandTrainingRecord::getSessionId).distinct().count();
    }
}
