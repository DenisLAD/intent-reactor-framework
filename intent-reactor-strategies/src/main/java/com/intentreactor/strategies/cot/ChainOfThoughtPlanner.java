package com.intentreactor.strategies.cot;

import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.util.PromptLoader;
import com.intentreactor.strategies.config.StrategiesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Chain-of-Thought decorator: injects a one-time reasoning scaffolding message into the session
 * and then delegates to the base ReACT planner. The scaffolding primes the LLM to show explicit
 * step-by-step reasoning before each decision.
 * <p>
 * Prompt file: classpath:prompts/strategies/cot-system{locale}.md
 * <p>
 * Activate with: intent-reactor.planning.strategy: cot
 */
public class ChainOfThoughtPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(ChainOfThoughtPlanner.class);
    private static final String COT_INJECTED_KEY = "cot_injected";
    private final Planner delegate;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String promptPath;

    public ChainOfThoughtPlanner(Planner delegate, StrategiesProperties strategiesProperties) {
        this.delegate = delegate;
        this.promptPath = strategiesProperties.getPrompts().getCotSystem();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(COT_INJECTED_KEY))) {
            String injection = promptLoader.load(promptPath, Map.of());
            if (injection.isBlank()) {
                injection = "[CHAIN OF THOUGHT ACTIVATED]\nFor every step, write your complete reasoning process BEFORE choosing an action.";
            }
            session.addMessage(Message.system(injection));
            session.getAttributes().put(COT_INJECTED_KEY, true);
            log.debug("[CoT] Injected chain-of-thought scaffolding into session {}", session.getId());
        }
        return delegate.plan(session, intent);
    }
}
