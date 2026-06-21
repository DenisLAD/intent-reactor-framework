package com.intentreactor.api.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * Base class for all IntentReactor framework events.
 *
 * <p>All events are published synchronously via Spring's {@code ApplicationEventPublisher}
 * at key points in the planning lifecycle. Subscribe to specific event types using
 * Spring's {@code @EventListener}:
 * <pre>{@code
 * @Component
 * public class AuditListener {
 *
 *     @EventListener
 *     public void onPlanCompleted(PlanCompletedEvent event) {
 *         auditLog.record(event.getSessionId(), "Plan completed: " + event.getFinalText());
 *     }
 *
 *     @EventListener
 *     public void onAnyEvent(IntentReactorEvent event) {
 *         // Catches all IntentReactor events
 *         metrics.increment("intentreactor.events", "type", event.getClass().getSimpleName());
 *     }
 * }
 * }</pre>
 *
 * <p>The framework provides a default listener ({@code IntentReactorEventLogger}) that
 * logs all events via SLF4J. Disable it via {@code intent-reactor.logging.enabled=false}.
 *
 * @see PlanStartedEvent
 * @see PlanCompletedEvent
 * @see PlanFailedEvent
 * @see PlanStepStartedEvent
 * @see PlanStepCompletedEvent
 * @see ConfirmationRequiredEvent
 * @see IntentAnalysisStartedEvent
 * @see IntentAnalysisCompletedEvent
 */
public abstract class IntentReactorEvent extends ApplicationEvent {

    private final String sessionId;
    private final LocalDateTime occurredAt;

    /**
     * Creates a new event.
     *
     * @param source    the component that published the event; must not be {@code null}
     * @param sessionId the identifier of the session this event relates to
     */
    protected IntentReactorEvent(Object source, String sessionId) {
        super(source);
        this.sessionId = sessionId;
        this.occurredAt = LocalDateTime.now();
    }

    /**
     * Returns the identifier of the session in which the event occurred.
     *
     * @return the session ID; may be a generated ID for stateless calls
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the timestamp at which the event was created.
     *
     * @return the event timestamp; never {@code null}
     */
    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
