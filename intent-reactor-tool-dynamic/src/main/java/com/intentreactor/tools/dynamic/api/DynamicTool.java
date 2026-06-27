package com.intentreactor.tools.dynamic.api;

import com.intentreactor.api.Tool;

/**
 * Marker interface for tools generated from LLM-authored ECMAScript scripts.
 * Exposes the script ID and version for lifecycle tracking by {@link com.intentreactor.tools.dynamic.tool.DynamicToolProvider}.
 */
public interface DynamicTool extends Tool {
    String getScriptId();

    String getScriptVersion();
}
