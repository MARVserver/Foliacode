package dev.marv.foliacode.model;

/**
 * How a call was found.
 *
 * <p>pasta only walked {@code MethodInsnNode}, so calls reached through a
 * method reference slipped past it. Recording the route makes it visible
 * which ones were caught and how.</p>
 */
public enum CallKind {

    /** An ordinary call instruction (INVOKEVIRTUAL / INVOKEINTERFACE / INVOKESTATIC, etc.). */
    DIRECT_CALL("direct call"),

    /** A method reference embedded in an invokedynamic bootstrap argument, e.g. {@code Block::setType}. */
    METHOD_REFERENCE("method reference");

    private final String displayName;

    CallKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
