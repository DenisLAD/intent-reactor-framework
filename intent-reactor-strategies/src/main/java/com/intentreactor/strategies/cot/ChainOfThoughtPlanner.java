package com.intentreactor.strategies.cot;

import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.strategies.config.StrategiesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chain-of-Thought decorator: injects a one-time reasoning scaffolding message into the session
 * and then delegates to the base ReACT planner. The scaffolding primes the LLM to show explicit
 * step-by-step reasoning before each decision.
 * <p>
 * Activate with: intent-reactor.planning.strategy: cot
 */
public class ChainOfThoughtPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(ChainOfThoughtPlanner.class);
    private static final String COT_INJECTED_KEY = "cot_injected";

    private static final String COT_INSTRUCTION =
            "[CHAIN OF THOUGHT ACTIVATED]\n" +
                    "For every step of the plan, explicitly write your complete reasoning process " +
                    "BEFORE choosing an action. Structure your thoughts as:\n" +
                    "1. What do I currently know?\n" +
                    "2. What is the next logical step and why?\n" +
                    "3. What tool (if any) should I use and with what parameters?\n" +
                    "Show the full chain of thought — do not skip intermediate reasoning steps.";

    private final Planner delegate;

    public ChainOfThoughtPlanner(Planner delegate, StrategiesProperties strategiesProperties) {
        this.delegate = delegate;
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(COT_INJECTED_KEY))) {
            session.addMessage(Message.system(COT_INSTRUCTION));
            session.getAttributes().put(COT_INJECTED_KEY, true);
            log.debug("[CoT] Injected chain-of-thought scaffolding into session {}", session.getId());
        }
        return delegate.plan(session, intent);
    }
}
