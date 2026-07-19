package dev.marv.foliacode.model;

/**
 * The overall conclusion of an analysis.
 *
 * <p>The first thing anyone wants to know is "so, will this run?". That
 * answer comes before any individual finding.</p>
 */
public enum Verdict {

    /** No Folia-incompatible calls were found. */
    READY("READY", "No Folia-incompatible calls were detected"),

    /** May work, but thread safety needs to be confirmed by hand. */
    NEEDS_REVIEW("NEEDS REVIEW", "Some call sites need review"),

    /** Folia will definitely throw on this plugin. */
    NOT_READY("NOT READY", "This plugin will fail on Folia");

    private final String label;
    private final String description;

    Verdict(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
