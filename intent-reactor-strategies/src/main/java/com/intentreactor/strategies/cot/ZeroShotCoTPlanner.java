package com.intentreactor.strategies.cot;

import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.util.PromptLoader;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.config.StrategySessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Zero-shot Chain-of-Thought decorator: appends the classic "Let's think step by step" trigger
 * to the current goal, which elicits chain-of-thought reasoning without few-shot examples.
 * <p>
 * Prompt file configured via intent-reactor.planning.strategies.prompts.zero-shot-cot-system
 * <p>
 * Activate with: intent-reactor.planning.strategy: zero-shot-cot
 */
public class ZeroShotCoTPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(ZeroShotCoTPlanner.class);
    private static final String ZS_COT_INJECTED_KEY = StrategySessionKeys.ZS_COT_INJECTED;

    private final Planner delegate;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String promptPath;

    public ZeroShotCoTPlanner(Planner delegate, StrategiesProperties strategiesProperties) {
        this.delegate = delegate;
        this.promptPath = strategiesProperties.getPrompts().getZeroShotCotSystem();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ZS_COT_INJECTED_KEY))) {
            String injection = promptLoader.load(promptPath, Map.of());
            if (injection.isBlank()) {
                injection = "[ZERO-SHOT COT] Let's think through this step by step. Think out loud and explain each reasoning step before making a decision.";
            }
            session.addMessage(Message.system(injection));
            session.getAttributes().put(ZS_COT_INJECTED_KEY, true);
            log.debug("[Zero-shot CoT] Injected reasoning trigger into session {}", session.getId());
        }
        return delegate.plan(session, intent);
    }
}
