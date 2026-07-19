package dev.marv.foliacode.verify;

/**
 * Overall classification of a server boot verification.
 *
 * <p>Exactly one outcome is chosen per run. When several problems are observed, the
 * outcome is decided by {@link BootLogAnalyzer} using a fixed priority order in which
 * environment failures outrank plugin failures, so a server that could not start is
 * never reported as a broken plugin.</p>
 */
public enum VerificationOutcome {

    /** The server finished booting and the plugin under test was enabled. */
    SUCCESS("Server booted and the plugin was enabled", false),

    /** The server finished booting but the plugin was never enabled. */
    PLUGIN_NOT_LOADED("The server booted but the plugin was never enabled", true),

    /** The plugin declares dependencies that are not installed. */
    MISSING_DEPENDENCY("The plugin requires other plugins that are not installed", true),

    /** The plugin is not marked as supporting Folia and was rejected. */
    FOLIA_UNSUPPORTED("The plugin is not marked as supporting Folia", true),

    /** A class could not be resolved, linked or verified. */
    CLASS_ERROR("A class failed to load, link or verify", true),

    /** The plugin threw at load or enable time. */
    RUNTIME_EXCEPTION("The plugin threw an exception while loading", true),

    /** The JVM could not allocate the heap, or exhausted it. */
    OUT_OF_MEMORY("The server JVM ran out of memory; this is a server setting, not a plugin defect", false),

    /** The boot did not reach a conclusion within the configured timeout. */
    TIMEOUT("The server did not finish booting before the timeout", false),

    /** The server process died without a recognisable cause. */
    SERVER_FAILED("The server process exited without completing its boot", false);

    private final String description;
    private final boolean attributableToPlugin;

    VerificationOutcome(String description, boolean attributableToPlugin) {
        this.description = description;
        this.attributableToPlugin = attributableToPlugin;
    }

    /** A human readable description of the outcome. */
    public String description() {
        return description;
    }

    /**
     * Whether this outcome is evidence about the plugin under test.
     *
     * <p>Outcomes such as {@link #OUT_OF_MEMORY} and {@link #TIMEOUT} describe the test
     * environment. Reporting them as plugin defects would be actively misleading, so
     * callers should use this flag before blaming the plugin.</p>
     *
     * @return {@code true} when the outcome says something about the plugin
     */
    public boolean isAttributableToPlugin() {
        return attributableToPlugin;
    }

    /** Whether the run proved the plugin loads on Folia. */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
