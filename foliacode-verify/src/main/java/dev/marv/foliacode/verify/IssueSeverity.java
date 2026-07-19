package dev.marv.foliacode.verify;

/**
 * Severity of an issue observed while booting a server.
 *
 * <p>Severity describes what the observation means for the verification run, not how
 * alarming the log line looks. A server-side configuration problem is fatal to the run
 * but says nothing about the plugin.</p>
 */
public enum IssueSeverity {

    /** The verification could not produce a verdict about the plugin. */
    FATAL("FATAL"),

    /** The plugin demonstrably failed to load or run. */
    ERROR("ERROR"),

    /** The plugin loaded, but something worth reviewing was logged. */
    WARNING("WARNING"),

    /** Contextual information only. */
    INFO("INFO");

    private final String label;

    IssueSeverity(String label) {
        this.label = label;
    }

    /** The display label. */
    public String label() {
        return label;
    }
}
