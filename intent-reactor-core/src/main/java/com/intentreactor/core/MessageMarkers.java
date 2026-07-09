package com.intentreactor.core;

/**
 * Protocol-level message markers inserted into session history by the execution engine.
 * Used both when creating messages and when parsing session history for display/reasoning.
 */
public final class MessageMarkers {

    public static final String TOOL_RESULT = "[TOOL_RESULT]";
    public static final String TOOL_ERROR = "[TOOL_ERROR]";
    public static final String REFLECTION = "[REFLECTION]";
    public static final String HINT = "[HINT]";

    private MessageMarkers() {
    }
}
