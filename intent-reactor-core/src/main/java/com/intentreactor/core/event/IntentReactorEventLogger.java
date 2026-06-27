package com.intentreactor.core.event;

import com.intentreactor.api.event.ConfirmationRequiredEvent;
import com.intentreactor.api.event.IntentAnalysisCompletedEvent;
import com.intentreactor.api.event.IntentAnalysisStartedEvent;
import com.intentreactor.api.event.PlanCompletedEvent;
import com.intentreactor.api.event.PlanFailedEvent;
import com.intentreactor.api.event.PlanStartedEvent;
import com.intentreactor.api.event.PlanStepCompletedEvent;
import com.intentreactor.api.event.PlanStepStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * SLF4J event listener that logs all {@code IntentReactorEvent} subtypes at INFO level.
 * Auto-registered by {@link com.intentreactor.core.config.IntentReactorAutoConfiguration};
 * disable via {@code intent-reactor.logging.enabled: false}.
 */
public class IntentReactorEventLogger {

    private static final Logger log = LoggerFactory.getLogger(IntentReactorEventLogger.class);

    @EventListener
    public void onIntentAnalysisStarted(IntentAnalysisStartedEvent e) {
        log.debug("[{}] Intent analysis started: \"{}\"", e.getSessionId(), e.getMessage());
    }

    @EventListener
    public void onIntentAnalysisCompleted(IntentAnalysisCompletedEvent e) {
        int intentCount = e.getResult().getIntents() != null ? e.getResult().getIntents().size() : 0;
        log.info("[{}] Intent analysis completed: {} intent(s) detected, uncertain={}",
                e.getSessionId(), intentCount, e.getResult().isUncertain());
    }

    @EventListener
    public void onPlanStarted(PlanStartedEvent e) {
        log.info("[{}] Plan started: \"{}\"", e.getSessionId(), e.getGoalDescription());
    }

    @EventListener
    public void onPlanStepStarted(PlanStepStartedEvent e) {
        log.debug("[{}] Step started: type={}, description=\"{}\"",
                e.getSessionId(), e.getStep().type(), e.getStep().description());
    }

    @EventListener
    public void onPlanStepCompleted(PlanStepCompletedEvent e) {
        boolean success = e.getResult() != null && e.getResult().isSuccess();
        log.info("[{}] Step completed: tool={}, success={}",
                e.getSessionId(),
                e.getStep().action() != null ? e.getStep().action().toolName() : "?",
                success);
    }

    @EventListener
    public void onConfirmationRequired(ConfirmationRequiredEvent e) {
        log.info("[{}] Confirmation required: action=\"{}\"",
                e.getSessionId(),
                e.getConfirmationRequest() != null ? e.getConfirmationRequest().getDescription() : "?");
    }

    @EventListener
    public void onPlanCompleted(PlanCompletedEvent e) {
        log.info("[{}] Plan completed: \"{}\"", e.getSessionId(), e.getFinalText());
    }

    @EventListener
    public void onPlanFailed(PlanFailedEvent e) {
        log.warn("[{}] Plan failed: {}", e.getSessionId(), e.getReason());
    }
}
