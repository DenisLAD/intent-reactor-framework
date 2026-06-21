package com.intentreactor.api;

import java.util.Map;

/**
 * The primary extension point of the IntentReactor framework.
 *
 * <p>A {@code Tool} represents a named, executable capability that the planning engine
 * (ReACT, Reflexion, LATS) can invoke during an intent-processing cycle. Any Spring bean
 * that implements this interface is automatically discovered by {@code DefaultToolProvider}
 * and made available to the planner.
 *
 * <h2>Implementing a custom tool</h2>
 * <pre>{@code
 * @Component
 * public class OrderLookupTool implements Tool {
 *
 *     private final OrderRepository orders;
 *
 *     public OrderLookupTool(OrderRepository orders) {
 *         this.orders = orders;
 *     }
 *
 *     @Override
 *     public String getName() { return "order_lookup"; }
 *
 *     @Override
 *     public String getDescription() {
 *         return "Looks up an order by its ID and returns status and shipping info.";
 *     }
 *
 *     @Override
 *     public Map<String, Object> getParameterSchema() {
 *         return Map.of(
 *             "type", "object",
 *             "properties", Map.of(
 *                 "orderId", Map.of("type", "string", "description", "The order identifier")
 *             ),
 *             "required", List.of("orderId")
 *         );
 *     }
 *
 *     @Override
 *     public ToolResult execute(ToolInput input) {
 *         String orderId = (String) input.getParameters().get("orderId");
 *         return orders.findById(orderId)
 *                 .map(o -> ToolResult.ok(Map.of("status", o.getStatus(), "eta", o.getEta())))
 *                 .orElse(ToolResult.error("Order not found: " + orderId));
 *     }
 *
 *     @Override
 *     public boolean isRisky() { return false; }
 * }
 * }</pre>
 *
 * <h2>Risky tools and confirmation</h2>
 * <p>When {@link #isRisky()} returns {@code true} and the framework is configured with
 * {@code intent-reactor.planning.autonomous=false}, plan execution is suspended and
 * a {@link ReactorResponse} with {@code status=AWAITING_CONFIRMATION} is returned.
 * The caller must invoke {@link IntentReactorService#proceedAfterConfirmation} to resume.
 *
 * <h2>Generator tools</h2>
 * <p>When {@link #isGenerator()} returns {@code true}, the tool is treated as a factory
 * that creates other tools at runtime (example: {@code DynamicScriptTool}).
 *
 * @see ToolInput
 * @see ToolResult
 * @see SimulatableTool
 * @see ToolProvider
 */
public interface Tool {

    /**
     * Returns the unique name by which the LLM planner identifies this tool.
     *
     * <p>Use lowercase, underscore-separated identifiers (e.g., {@code "order_lookup"},
     * {@code "send_email"}). The name is embedded verbatim in the prompt sent to the LLM.
     *
     * @return the tool name; never {@code null} or blank
     */
    String getName();

    /**
     * Returns a human-readable description of what this tool does.
     *
     * <p>The description is included in the LLM prompt. Write it in imperative form
     * and mention expected inputs and outputs. Concise, precise descriptions
     * improve planner accuracy.
     *
     * @return the tool description; never {@code null}
     */
    String getDescription();

    /**
     * Returns a JSON Schema object describing the parameters this tool accepts.
     *
     * <p>The schema is serialized to JSON and embedded in the LLM prompt.
     * Use standard JSON Schema vocabulary ({@code type}, {@code properties},
     * {@code required}, {@code description} per property).
     *
     * @return a non-null {@code Map} representing a JSON Schema {@code object}
     */
    Map<String, Object> getParameterSchema();

    /**
     * Executes this tool with the given input and returns a result.
     *
     * <p>Implementations must be thread-safe — the framework may call this method
     * concurrently from multiple sessions. Catch all checked exceptions internally
     * and return them as {@link ToolResult#error(String)}.
     *
     * @param input the execution context with resolved parameters and session ID; never {@code null}
     * @return a non-null {@link ToolResult} indicating success or failure
     */
    ToolResult execute(ToolInput input);

    /**
     * Returns {@code true} if this tool performs a destructive or irreversible action
     * (e.g., sending an email, processing a payment, deleting a record).
     *
     * <p>When {@code true} and {@code intent-reactor.planning.autonomous=false},
     * plan execution is suspended until the caller confirms via
     * {@link IntentReactorService#proceedAfterConfirmation}.
     *
     * @return {@code true} if the tool requires user confirmation in non-autonomous mode
     */
    boolean isRisky();

    /**
     * Returns {@code true} if this tool generates other tools at runtime.
     *
     * <p>Generator tools are not presented to the LLM as callable tools in the standard
     * tool list. Instead, specialised {@link ToolProvider} implementations (such as
     * {@code DynamicToolProvider}) use them to produce the actual tool list at request time.
     * The default implementation returns {@code false}.
     *
     * @return {@code true} if this tool is a tool factory; {@code false} otherwise
     */
    default boolean isGenerator() {
        return false;
    }
}
