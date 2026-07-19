package dev.marv.foliacode.model;

import java.util.Objects;

/**
 * A single finding: which class, which method, which line, and what it calls.
 *
 * @param className        detected class name (dot-separated)
 * @param methodName       detected method name
 * @param methodDescriptor descriptor of the detected method
 * @param lineNumber       line number, or {@code -1} when no debug info is present
 * @param callKind         how the call was found
 * @param calleeOwner      internal name of the class actually called, which may be a subtype of the rule's owner
 * @param api              the rule that matched
 */
public record Finding(
        String className,
        String methodName,
        String methodDescriptor,
        int lineNumber,
        CallKind callKind,
        String calleeOwner,
        UnsafeApi api
) {

    public Finding {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(callKind, "callKind");
        Objects.requireNonNull(calleeOwner, "calleeOwner");
        Objects.requireNonNull(api, "api");
    }

    public Severity severity() {
        return api.severity();
    }

    public Category category() {
        return api.category();
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

    /**
     * Whether the call reached the rule through a subtype rather than the
     * declaring type itself.
     *
     * <p>pasta matched owners by exact name only, so calls like
     * {@code Player.teleport} were never attributed to the
     * {@code Entity.teleport} rule. Flagging the distinction shows the reader
     * that the subtype case was caught.</p>
     *
     * @return true if the call went through a subtype
     */
    public boolean isViaSubtype() {
        return !calleeOwner.equals(api.owner());
    }

    /** Simple name of the class actually called. */
    public String calleeSimpleName() {
        int index = calleeOwner.lastIndexOf('/');
        return index < 0 ? calleeOwner : calleeOwner.substring(index + 1);
    }
}
