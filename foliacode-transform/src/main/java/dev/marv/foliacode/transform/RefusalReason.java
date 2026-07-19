package dev.marv.foliacode.transform;

/**
 * Why a call site was left alone.
 *
 * <p>Refusals are the main output of this tool, not a footnote to it. A rewriter
 * that silently skips what it cannot handle leaves the reader believing the job is
 * done. Each of these says what stopped the rewrite, so the remaining work is a list
 * rather than a mystery.</p>
 */
public enum RefusalReason {

    /** No rewrite exists that could be justified from the bytecode alone. */
    NO_PROVEN_REWRITE(
            "No proven-safe rewrite exists for this API. It needs a human decision about "
                    + "which region should own the work."),

    /**
     * The result of the call is used.
     *
     * <p>The asynchronous replacements do not produce the value the synchronous call
     * did — {@code teleportAsync} has not finished when it returns, and the Folia
     * schedulers hand out no {@code BukkitTask}. Where the result is discarded that
     * difference is unobservable. Where it is used, it is the whole meaning of the
     * code.</p>
     */
    RESULT_IS_USED(
            "The result of this call is used. The asynchronous replacement cannot produce "
                    + "the same value, so rewriting it would change what the code means."),

    /**
     * The call is a method reference.
     *
     * <p>{@code list.forEach(Entity::remove)} has no call instruction to replace. The
     * invocation happens inside a class the JVM generates at runtime from the handle
     * in the invokedynamic bootstrap.</p>
     */
    METHOD_REFERENCE(
            "This is a method reference. The call happens inside a lambda class generated "
                    + "at runtime, so there is no call site to rewrite."),

    /** The call lives inside a shaded nested JAR. */
    INSIDE_NESTED_JAR(
            "This call is inside a JAR shaded into the plugin. Rewriting another project's "
                    + "code inside your artifact is not something this tool will do unasked.");

    private final String explanation;

    RefusalReason(String explanation) {
        this.explanation = explanation;
    }

    /** Why the rewrite did not happen, in a form fit to show a user. */
    public String explanation() {
        return explanation;
    }
}
