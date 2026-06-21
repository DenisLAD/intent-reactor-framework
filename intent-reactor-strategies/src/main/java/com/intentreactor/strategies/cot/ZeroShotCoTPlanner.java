package com.intentreactor.strategies.cot;

import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zero-shot Chain-of-Thought decorator: appends the classic "Let's think step by step" trigger
 * to the current goal, which elicits chain-of-thought reasoning without few-shot examples.
 * <p>
 * Activate with: intent-reactor.planning.strategy: zero-shot-cot
 */
public class ZeroShotCoTPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(ZeroShotCoTPlanner.class);
    private static final String ZS_COT_INJECTED_KEY = "zs_cot_injected";

    private final Planner delegate;

    public ZeroShotCoTPlanner(Planner delegate) {
        this.delegate = delegate;
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ZS_COT_INJECTED_KEY))) {
            session.addMessage(Message.system(
                    "[ZERO-SHOT COT] Давай разберём это по шагам. " +
                            "Мысли вслух и объясняй каждый шаг своих рассуждений перед тем как принять решение."));
            session.getAttributes().put(ZS_COT_INJECTED_KEY, true);
            log.debug("[Zero-shot CoT] Injected reasoning trigger into session {}", session.getId());
        }
        return delegate.plan(session, intent);
    }
}
