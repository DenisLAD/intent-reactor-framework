package com.intentreactor.tools.dynamic.sandbox;

import com.intentreactor.api.ToolResult;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RhinoSandbox {

    private final TimeLimitedContextFactory contextFactory;
    private final SandboxClassShutter classShutter;
    /**
     * In-memory compiled-script cache keyed by {@code scriptId|version}.
     * Does NOT survive application restart or cluster node failover — each node
     * compiles on first use, which is acceptable since compilation is a one-time cost.
     */
    private final ConcurrentHashMap<String, Script> compiledCache = new ConcurrentHashMap<>();

    public RhinoSandbox(TimeLimitedContextFactory contextFactory,
                        SandboxClassShutter classShutter) {
        this.contextFactory = contextFactory;
        this.classShutter = classShutter;
    }

    public Script compile(String scriptId, String version, String jsCode) {
        String cacheKey = scriptId + "|" + version;
        return compiledCache.computeIfAbsent(cacheKey, k -> {
            Context cx = contextFactory.enterContext();
            try {
                cx.setClassShutter(classShutter);
                return cx.compileString(jsCode, scriptId + "-" + version, 1, null);
            } finally {
                Context.exit();
            }
        });
    }

    public ToolResult execute(Script compiled, String scriptId, Map<String, Object> parameters) {
        Context cx = contextFactory.enterContext();
        try {
            cx.setClassShutter(classShutter);
            cx.putThreadLocal("execution", new TimeLimitedContextFactory.ScriptExecution());

            Scriptable scope = cx.initSafeStandardObjects();

            Scriptable inputObj = cx.newObject(scope);
            if (parameters != null) {
                for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                    ScriptableObject.putProperty(inputObj, entry.getKey(),
                            Context.javaToJS(entry.getValue(), scope));
                }
            }
            scope.put("input", scope, inputObj);

            compiled.exec(cx, scope);

            Object execFunc = scope.get("execute", scope);
            if (!(execFunc instanceof Function)) {
                return ToolResult.error("Script must define function execute(input)");
            }

            Object result = ((Function) execFunc).call(cx, scope, scope, new Object[]{inputObj});
            return ToolResult.ok(unwrapResult(result));

        } catch (ScriptTimeoutException e) {
            return ToolResult.error("Script execution timed out");
        } catch (RhinoException e) {
            return ToolResult.error("Script error: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Execution failed: " + e.getMessage());
        } finally {
            cx.removeThreadLocal("execution");
            Context.exit();
        }
    }

    public void validate(String jsCode, String scriptName) {
        Context cx = contextFactory.enterContext();
        try {
            cx.setClassShutter(classShutter);
            cx.compileString(jsCode, scriptName, 1, null);
        } catch (RhinoException e) {
            throw new IllegalArgumentException("JavaScript syntax error: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    public void invalidateCache(String scriptId, String version) {
        compiledCache.remove(scriptId + "|" + version);
    }

    private Object unwrapResult(Object jsResult) {
        if (jsResult == null || jsResult == Undefined.instance) return null;
        if (jsResult instanceof NativeObject) return Context.jsToJava(jsResult, Map.class);
        if (jsResult instanceof String || jsResult instanceof Number || jsResult instanceof Boolean) {
            return jsResult;
        }
        return Context.toString(jsResult);
    }
}
