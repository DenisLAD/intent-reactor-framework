package com.intentreactor.tools.dynamic.sandbox;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

import java.time.Duration;

/**
 * Rhino {@link ContextFactory} that enforces a wall-clock timeout on script execution.
 * Runs in interpreted mode ({@code optimizationLevel=-1}) so that {@code observeInstructionCount}
 * is called periodically; throws {@link ScriptTimeoutException} when the elapsed time exceeds the limit.
 */
public class TimeLimitedContextFactory extends ContextFactory {

    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10_000;

    private final long maxExecutionMillis;

    public TimeLimitedContextFactory(Duration maxExecutionTime) {
        this.maxExecutionMillis = maxExecutionTime.toMillis();
    }

    @Override
    protected Context makeContext() {
        Context cx = super.makeContext();
        // Without optimizationLevel=-1 (interpreted mode), observeInstructionCount is never called
        cx.setOptimizationLevel(-1);
        cx.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
        return cx;
    }

    @Override
    protected void observeInstructionCount(Context cx, int instructionCount) {
        ScriptExecution exec = (ScriptExecution) cx.getThreadLocal("execution");
        if (exec != null) {
            long elapsed = System.currentTimeMillis() - exec.startTime;
            if (elapsed > maxExecutionMillis) {
                throw new ScriptTimeoutException(
                        "Script timed out after " + elapsed + "ms (limit: " + maxExecutionMillis + "ms)");
            }
        }
    }

    public static class ScriptExecution {
        public final long startTime = System.currentTimeMillis();
    }
}
