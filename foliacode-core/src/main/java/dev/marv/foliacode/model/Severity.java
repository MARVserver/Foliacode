package dev.marv.foliacode.model;

/**
 * How serious a finding is.
 *
 * <p>Severity is assigned by what actually happens on Folia, not by how risky
 * the call feels. Every level below describes an observable outcome.</p>
 */
public enum Severity {

    /** Folia throws on this call; the code cannot work. */
    CRITICAL("CRITICAL", "Throws on Folia; this code cannot work"),

    /** Thread-safety violation that can crash the server or corrupt data. */
    HIGH("HIGH", "Thread-safety violation; risks crashes or data corruption"),

    /** Whether this is safe depends on the calling thread. Needs review. */
    MEDIUM("MEDIUM", "Safety depends on the caller's thread context"),

    /** A limit of static analysis rather than a defect. Needs a human. */
    INFO("INFO", "Informational; static analysis cannot decide");

    private final String label;
    private final String description;

    Severity(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /**
     * Tests whether this severity is at least as serious as a threshold.
     *
     * <p>Constants are declared most-serious first, so a lower ordinal means
     * a more serious finding.</p>
     *
     * @param threshold the threshold to compare against
     * @return true if this severity is at least as serious as the threshold
     */
    public boolean isAtLeast(Severity threshold) {
        return this.ordinal() <= threshold.ordinal();
    }

    /**
     * Resolves a severity by name, ignoring case.
     *
     * @param value the text to resolve
     * @return the matching severity
     * @throws IllegalArgumentException if nothing matches
     */
    public static Severity parse(String value) {
        for (Severity severity : values()) {
            if (severity.name().equalsIgnoreCase(value)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown severity: " + value);
    }
}
