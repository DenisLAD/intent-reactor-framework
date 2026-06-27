package com.intentreactor.tools.dynamic.api;

/**
 * Lifecycle state of a dynamic script: {@code ACTIVE} scripts are loaded by
 * {@link com.intentreactor.tools.dynamic.tool.DynamicToolProvider}; {@code ARCHIVED} scripts are ignored.
 */
public enum ScriptStatus {
    ACTIVE,
    ARCHIVED
}
