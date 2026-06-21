package com.intentreactor.tools.dynamic.sandbox;

import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Script;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RhinoSandboxTest {

    private RhinoSandbox sandbox;

    @BeforeEach
    void setUp() {
        TimeLimitedContextFactory factory = new TimeLimitedContextFactory(Duration.ofSeconds(2));
        SandboxClassShutter shutter = new SandboxClassShutter(List.of());
        sandbox = new RhinoSandbox(factory, shutter);
    }

    @Test
    void executeSimpleAddition_returnsResult() {
        String js = "function execute(input) { return input.x + input.y; }";
        Script compiled = sandbox.compile("add-test", "v1", js);
        ToolResult result = sandbox.execute(compiled, "add-test", Map.of("x", 3, "y", 4));
        assertThat(result.isSuccess()).isTrue();
        // Rhino returns JS numbers as Double (7.0), toString gives "7.0"
        assertThat(Double.parseDouble(result.getData().toString())).isEqualTo(7.0);
    }

    @Test
    void executeStringManipulation_returnsResult() {
        String js = "function execute(input) { return input.text.toUpperCase(); }";
        Script compiled = sandbox.compile("upper-test", "v1", js);
        ToolResult result = sandbox.execute(compiled, "upper-test", Map.of("text", "hello"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toString()).isEqualTo("HELLO");
    }

    @Test
    void executeScript_timesOut() {
        RhinoSandbox shortTimeout = new RhinoSandbox(
                new TimeLimitedContextFactory(Duration.ofMillis(100)),
                new SandboxClassShutter(List.of()));
        String js = "function execute(input) { var i = 0; while(true) { i++; } return i; }";
        Script compiled = shortTimeout.compile("loop-test", "v1", js);
        ToolResult result = shortTimeout.execute(compiled, "loop-test", Map.of());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("timed out");
    }

    @Test
    void executeScript_blocksSystemAccess() {
        String js = "function execute(input) { java.lang.System.exit(0); return 'ok'; }";
        Script compiled = sandbox.compile("sys-test", "v1", js);
        ToolResult result = sandbox.execute(compiled, "sys-test", Map.of());
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void executeScript_blocksFileAccess() {
        String js = "function execute(input) { var f = new java.io.File('/tmp/x'); return 'ok'; }";
        Script compiled = sandbox.compile("file-test", "v1", js);
        ToolResult result = sandbox.execute(compiled, "file-test", Map.of());
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void validate_throwsOnSyntaxError() {
        assertThatThrownBy(() -> sandbox.validate("function execute(input) { BAD %%%", "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("syntax error");
    }

    @Test
    void validate_passesOnValidCode() {
        assertThatCode(() -> sandbox.validate(
                "function execute(input) { return 'ok'; }", "good"))
                .doesNotThrowAnyException();
    }

    @Test
    void compiledScriptIsCached() {
        String js = "function execute(input) { return 42; }";
        Script first = sandbox.compile("cache-test", "v1", js);
        Script second = sandbox.compile("cache-test", "v1", js);
        assertThat(first).isSameAs(second);
    }

    @Test
    void invalidateCache_forcesRecompile() {
        String js = "function execute(input) { return 1; }";
        Script first = sandbox.compile("inv-test", "v1", js);
        sandbox.invalidateCache("inv-test", "v1");
        Script second = sandbox.compile("inv-test", "v1", js);
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void missingExecuteFunction_returnsError() {
        String js = "var x = 42;";
        Script compiled = sandbox.compile("nofunc-test", "v1", js);
        ToolResult result = sandbox.execute(compiled, "nofunc-test", Map.of());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("execute");
    }
}
