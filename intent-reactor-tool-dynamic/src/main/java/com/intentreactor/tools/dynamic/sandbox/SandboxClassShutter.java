package com.intentreactor.tools.dynamic.sandbox;

import org.mozilla.javascript.ClassShutter;

import java.util.List;
import java.util.Set;

/**
 * Rhino {@link ClassShutter} that blocks access to dangerous Java packages ({@code java.io},
 * {@code java.net}, {@code java.lang.System}, {@code Runtime}, {@code Thread}, reflection, etc.)
 * while allowing safe utility classes and an optional user-supplied allowlist.
 */
public class SandboxClassShutter implements ClassShutter {

    private static final Set<String> ALWAYS_DENIED_PREFIXES_SET = Set.of(
            "java.io.",
            "java.net.",
            "java.nio.",
            "sun.",
            "com.sun.",
            "jdk.",
            "java.lang.reflect.",
            "java.lang.invoke."
    );

    private static final Set<String> ALWAYS_DENIED_EXACT = Set.of(
            "java.lang.System",
            "java.lang.Runtime",
            "java.lang.Thread",
            "java.lang.ProcessBuilder",
            "java.lang.Class"
    );

    private static final Set<String> ALWAYS_ALLOWED = Set.of(
            "java.lang.Math",
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Double",
            "java.lang.Boolean",
            "java.lang.Long",
            "java.lang.Number",
            "java.util.Map",
            "java.util.List",
            "java.util.ArrayList",
            "java.util.HashMap"
    );

    private final Set<String> extraAllowedClasses;

    public SandboxClassShutter(List<String> extraAllowedClasses) {
        this.extraAllowedClasses = Set.copyOf(extraAllowedClasses);
    }

    @Override
    public boolean visibleToScripts(String fullClassName) {
        for (String prefix : ALWAYS_DENIED_PREFIXES_SET) {
            if (fullClassName.startsWith(prefix)) return false;
        }
        if (ALWAYS_DENIED_EXACT.contains(fullClassName)) return false;
        if (ALWAYS_ALLOWED.contains(fullClassName)) return true;
        if (extraAllowedClasses.contains(fullClassName)) return true;
        return fullClassName.startsWith("org.mozilla.javascript.");
    }
}
