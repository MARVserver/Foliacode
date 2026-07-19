package dev.marv.foliacode.transform;

import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.UnsafeApi;

import java.util.Objects;

/**
 * What happened at one call site: rewritten, or refused and why.
 *
 * @param className        declaring class (dot-separated)
 * @param methodName       declaring method
 * @param methodDescriptor descriptor of the declaring method
 * @param lineNumber       line number, or {@code -1} when no debug info is present
 * @param calleeOwner      internal name of the class actually called
 * @param api              the analyzer rule this site matched
 * @param rule             the rewrite applied, or {@code null} if none was
 * @param refusal          why nothing was rewritten, or {@code null} if it was
 */
public record TransformAction(
        String className,
        String methodName,
        String methodDescriptor,
        int lineNumber,
        String calleeOwner,
        UnsafeApi api,
        TransformRule rule,
        RefusalReason refusal
) {

    public TransformAction {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(calleeOwner, "calleeOwner");
        Objects.requireNonNull(api, "api");
        if ((rule == null) == (refusal == null)) {
            throw new IllegalArgumentException(
                    "A call site is either rewritten or refused, never both and never neither");
        }
    }

    /**
     * Records a rewritten call site.
     *
     * @param className        declaring class
     * @param methodName       declaring method
     * @param methodDescriptor descriptor of the declaring method
     * @param lineNumber       line number
     * @param calleeOwner      internal name of the class called
     * @param api              the analyzer rule
     * @param rule             the rewrite applied
     * @return the action
     */
    public static TransformAction rewritten(
            String className, String methodName, String methodDescriptor, int lineNumber,
            String calleeOwner, UnsafeApi api, TransformRule rule) {
        return new TransformAction(className, methodName, methodDescriptor, lineNumber,
                calleeOwner, api, Objects.requireNonNull(rule, "rule"), null);
    }

    /**
     * Records a refused call site.
     *
     * @param className        declaring class
     * @param methodName       declaring method
     * @param methodDescriptor descriptor of the declaring method
     * @param lineNumber       line number
     * @param calleeOwner      internal name of the class called
     * @param api              the analyzer rule
     * @param refusal          why the rewrite did not happen
     * @return the action
     */
    public static TransformAction refused(
            String className, String methodName, String methodDescriptor, int lineNumber,
            String calleeOwner, UnsafeApi api, RefusalReason refusal) {
        return new TransformAction(className, methodName, methodDescriptor, lineNumber,
                calleeOwner, api, null, Objects.requireNonNull(refusal, "refusal"));
    }

    /** Whether the call site was rewritten. */
    public boolean isRewritten() {
        return rule != null;
    }

    /** Severity of the underlying finding. */
    public Severity severity() {
        return api.severity();
    }

    /** Whether the rewrite changes observable behaviour in a way worth stating. */
    public boolean hasCaveat() {
        return rule != null && rule.hasCaveat();
    }

    /**
     * Human-readable call site.
     *
     * @return for example {@code com.example.MyPlugin#onEnable (line 42)}
     */
    public String location() {
        String base = className + "#" + methodName;
        return lineNumber > 0 ? base + " (line " + lineNumber + ")" : base;
    }
}
