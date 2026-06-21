package com.intentreactor.core.planner;

import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class MicrometerPlannerDecorator implements Planner {

    private final Planner delegate;
    private final MeterRegistry registry;
    private final String strategyName;

    public MicrometerPlannerDecorator(Planner delegate, MeterRegistry registry, String strategyName) {
        this.delegate = delegate;
        this.registry = registry;
        this.strategyName = strategyName;
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        return Timer.builder("intent_reactor.plan.duration")
                .tag("strategy", strategyName)
                .description("Time to produce a plan step")
                .register(registry)
                .record(() -> {
                    Plan p = delegate.plan(session, intent);
                    if (p != null && !p.steps().isEmpty()) {
                        registry.counter("intent_reactor.plan.steps",
                                        "strategy", strategyName,
                                        "type", p.steps().get(0).type().name())
                                .increment();
                    }
                    return p;
                });
    }
}
