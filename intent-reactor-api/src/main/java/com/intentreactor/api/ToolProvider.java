package com.intentreactor.api;

import java.util.List;

/**
 * Resolves the list of {@link Tool} instances available for a given session.
 *
 * <p>The default implementation ({@code DefaultToolProvider}) returns all Spring beans
 * that implement {@link Tool} and have {@link Tool#isGenerator()} == {@code false}.
 *
 * <p>Replace with a custom implementation to implement dynamic tool loading,
 * per-user tool filtering, or to integrate the dynamic scripting module
 * ({@code DynamicToolProvider} from {@code intent-reactor-tool-dynamic}).
 *
 * @see Tool
 * @see SessionState
 */
public interface ToolProvider {

    /**
     * Returns the tools available for the given session at this point in time.
     *
     * <p>The returned list is embedded in the LLM prompt as a tool catalogue.
     * Implementations may filter tools based on session attributes (e.g., user role,
     * feature flags stored in {@link SessionState#getAttributes()}).
     *
     * @param sessionState the current conversation state; never {@code null}
     * @return a non-null, non-empty list of available tools
     */
    List<Tool> getAvailableTools(SessionState sessionState);
}
