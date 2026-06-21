package com.intentreactor.tools.dynamic.api;

import com.intentreactor.api.Tool;

public interface DynamicTool extends Tool {
    String getScriptId();

    String getScriptVersion();
}
