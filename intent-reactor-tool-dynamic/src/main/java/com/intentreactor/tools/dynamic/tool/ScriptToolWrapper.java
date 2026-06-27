package com.intentreactor.tools.dynamic.tool;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.tools.dynamic.api.DynamicTool;
import com.intentreactor.tools.dynamic.model.ScriptDefinition;
import com.intentreactor.tools.dynamic.sandbox.RhinoSandbox;
import org.mozilla.javascript.Script;

import java.util.Map;

/**
 * Adapts a {@link ScriptDefinition} into a callable {@link com.intentreactor.api.Tool} by
 * pre-compiling the ECMAScript 5.1 source via the Rhino sandbox at construction time and
 * delegating {@link #execute} to {@link RhinoSandbox#execute}.
 */
public class ScriptToolWrapper implements DynamicTool {

    private final ScriptDefinition definition;
    private final RhinoSandbox sandbox;
    private final Script compiledScript;

    public ScriptToolWrapper(ScriptDefinition definition, RhinoSandbox sandbox) {
        this.definition = definition;
        this.sandbox = sandbox;
        this.compiledScript = sandbox.compile(
                definition.getId(), definition.getVersion(), definition.getCode());
    }

    @Override
    public String getName() {
        return definition.getName().replace('-', '_').replace(' ', '_');
    }

    @Override
    public String getDescription() {
        return definition.getDescription() + " [dynamic, v" + definition.getVersion() + "]";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return definition.getParameterSchema() != null
                ? definition.getParameterSchema()
                : Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolInput input) {
        return sandbox.execute(compiledScript, definition.getId(), input.getParameters());
    }

    @Override
    public boolean isRisky() {
        return definition.isRisky();
    }

    @Override
    public boolean isGenerator() {
        return false;
    }

    @Override
    public String getScriptId() {
        return definition.getId();
    }

    @Override
    public String getScriptVersion() {
        return definition.getVersion();
    }
}
