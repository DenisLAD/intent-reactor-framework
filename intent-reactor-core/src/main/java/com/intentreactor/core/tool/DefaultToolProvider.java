package com.intentreactor.core.tool;

import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;

import java.util.Collections;
import java.util.List;

public class DefaultToolProvider implements ToolProvider {

    private final List<Tool> tools;

    public DefaultToolProvider(List<Tool> tools) {
        this.tools = tools;
    }

    @Override
    public List<Tool> getAvailableTools(SessionState sessionState) {
        return filterBySession(Collections.unmodifiableList(tools), sessionState);
    }

    /**
     * Override in subclasses to filter tools based on session context,
     * e.g. by tenant ID in session.attributes or user permissions.
     */
    protected List<Tool> filterBySession(List<Tool> tools, SessionState session) {
        return tools;
    }
}
