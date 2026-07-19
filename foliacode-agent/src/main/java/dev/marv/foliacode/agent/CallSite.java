package dev.marv.foliacode.agent;

import dev.marv.foliacode.model.UnsafeApi;

import java.util.Objects;

/**
 * One instrumented call site.
 *
 * <p>The static analyzer describes a call site it found in bytecode. This
 * describes the same place after instrumentation, carrying the identifier the
 * injected code passes back at runtime.</p>
 *
 * @param id               identifier passed to {@link RuntimeRecorder#observe(int)}
 * @param className        declaring class (dot-separated)
 * @param methodName       declaring method
 * @param methodDescriptor descriptor of the declaring method
 * @param lineNumber       line number, or {@code -1} when the class carries no debug info
 * @param calleeOwner      internal name of the class actually called
 * @param api              the rule this site matched
 */
public record CallSite(
        int id,
        String className,
        String methodName,
        String methodDescriptor,
        int lineNumber,
        String calleeOwner,
        UnsafeApi api
) {

    public CallSite {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(calleeOwner, "calleeOwner");
        Objects.requireNonNull(api, "api");
    }

    /** Whether the line number is known. */
    public boolean hasLineNumber() {
        return lineNumber > 0;
    }

    /**
     * Human-readable call site.
     *
     * @return for example {@code com.example.MyPlugin#onEnable (line 42)}
     */
    public String location() {
        String base = className + "#" + methodName;
        return hasLineNumber() ? base + " (line " + lineNumber + ")" : base;
    }
}
