package com.intentreactor.api;

/**
 * Strategy interface for constructing a {@link Plan} from the current session state and intent.
 *
 * <p>Planners are <strong>stateless</strong> — all conversation context lives in
 * {@link SessionState}. The framework calls {@link #plan} once per ReACT iteration:
 * after each tool execution, the result is appended to the session as a SYSTEM message
 * and the planner is called again. This loop continues until the plan is complete.
 *
 * <p>Three built-in strategies are available via {@code intent-reactor.planning.strategy}:
 * <ul>
 *   <li>{@code react} — {@code DefaultReACTPlanner}: baseline single-step planning</li>
 *   <li>{@code reflexion} — {@code ReflexionPlanner}: adds self-reflection after failures</li>
 *   <li>{@code lats} — {@code LATSPlanner}: Monte Carlo tree search over possible actions</li>
 * </ul>
 *
 * <h2>Implementing a custom planner</h2>
 * <pre>{@code
 * @Component
 * @Primary
 * public class ChainOfThoughtPlanner implements Planner {
 *
 *     private final ChatClient chatClient;
 *
 *     public ChainOfThoughtPlanner(ChatClient chatClient) {
 *         this.chatClient = chatClient;
 *     }
 *
 *     @Override
 *     public Plan plan(SessionState session, IntentAnalysisResult intent) {
 *         String goal = session.getPlanState().getGoalDescription();
 *
 *         // Call LLM, parse response ...
 *         String toolName = resolveNextTool(goal, session.getMessages());
 *
 *         if (toolName == null) {
 *             return new SimplePlan(List.of(SimplePlanStep.done("Goal achieved.")));
 *         }
 *
 *         Action action = new SimpleAction(toolName, Map.of("query", goal));
 *         return new SimplePlan(List.of(SimplePlanStep.act(action, "Calling " + toolName, false)));
 *     }
 * }
 * }</pre>
 *
 * @see Plan
 * @see SessionState
 * @see IntentAnalysisResult
 * @see SimplePlan
 * @see SimplePlanStep
 */
public interface Planner {

    /**
     * Produces a plan for the current state of the conversation.
     *
     * <p>This method is called repeatedly — once per ReACT cycle iteration — until
     * the returned plan contains a terminal step ({@link StepType#DONE} or
     * {@link StepType#FAIL}) or the configured {@code max-steps} limit is reached.
     *
     * <p>Implementations must not modify {@code sessionState} directly; all state
     * mutation is the responsibility of the execution engine.
     *
     * @param sessionState the full current conversation state, including message history
     *                     and plan progress; never {@code null}
     * @param intent       the intent analysis result for the current user message;
     *                     never {@code null}
     * @return a non-null {@link Plan} containing at least one step
     */
    Plan plan(SessionState sessionState, IntentAnalysisResult intent);
}
