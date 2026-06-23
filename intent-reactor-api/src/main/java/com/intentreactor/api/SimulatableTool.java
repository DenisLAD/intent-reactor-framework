package com.intentreactor.api;

/**
 * Extension of {@link Tool} that supports side-effect-free simulation.
 *
 * <p>Implement this interface to enable the LATS planner
 * ({@code intent-reactor.planning.strategy=lats}) to evaluate tool outcomes
 * during Monte Carlo Tree Search without committing real side effects.
 * The planner calls {@link #simulate} during the expansion phase and
 * {@link Tool#execute} only when a final path is confirmed.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Component
 * public class PriceCalculatorTool implements SimulatableTool {
 *
 *     @Override
 *     public ToolResult execute(ToolInput input) {
 *         // Real call — may hit external pricing API
 *         return ToolResult.ok(pricingService.calculate(input.getParameters()));
 *     }
 *
 *     @Override
 *     public ToolResult simulate(ToolInput input) {
 *         // Fast estimate — no network calls
 *         double qty = ((Number) input.getParameters().get("quantity")).doubleValue();
 *         return ToolResult.ok(Map.of("estimatedTotal", qty * 9.99));
 *     }
 *
 *     // getName, getDescription, getParameterSchema, isRisky ...
 * }
 * }</pre>
 *
 * <p>When {@code intent-reactor.planning.lats.allow-real-actions-in-simulation=true},
 * the LATS planner calls {@link Tool#execute} instead of {@link #simulate}.
 *
 * <p>Callers check for this capability with {@code instanceof SimulatableTool} — this is
 * intentional. Adding {@code canSimulate()} to the base {@link Tool} interface would pollute
 * it with LATS-specific concerns; the {@code instanceof} pattern is the correct Java idiom
 * for optional capabilities expressed via marker sub-interfaces.
 *
 * @see Tool
 * @see ToolInput
 * @see ToolResult
 */
public interface SimulatableTool extends Tool {

    /**
     * Executes the tool logic without causing real side effects.
     *
     * <p>The result may be approximate. The framework discards simulated results
     * after scoring and never includes them in session history.
     *
     * @param input the execution context with resolved parameters; never {@code null}
     * @return a non-null {@link ToolResult}; may be approximate but must be non-null
     */
    ToolResult simulate(ToolInput input);
}
