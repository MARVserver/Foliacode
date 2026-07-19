package dev.marv.foliacode.verify;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The result of booting a real server with the plugin installed.
 *
 * <p>This complements the static analysis performed by {@code foliacode-core}: static
 * analysis predicts what a plugin might do, this records what actually happened.</p>
 *
 * @param outcome             the overall classification
 * @param bootDuration        how long the boot ran before a conclusion was reached
 * @param issues              every problem detected, in priority order
 * @param missingDependencies names of plugins the plugin under test needs but which were absent
 * @param logTail             the retained tail of the server log
 * @param minecraftVersion    the Minecraft version that was booted, or {@code null} if unknown
 */
public record ServerVerificationResult(
        VerificationOutcome outcome,
        Duration bootDuration,
        List<VerificationIssue> issues,
        List<String> missingDependencies,
        List<String> logTail,
        String minecraftVersion
) {

    public ServerVerificationResult {
        Objects.requireNonNull(outcome, "outcome");
        bootDuration = bootDuration == null ? Duration.ZERO : bootDuration;
        issues = issues == null ? List.of() : List.copyOf(issues);
        missingDependencies = missingDependencies == null ? List.of() : List.copyOf(missingDependencies);
        logTail = logTail == null ? List.of() : List.copyOf(logTail);
    }

    /** Whether the plugin was proven to load on Folia. */
    public boolean isSuccess() {
        return outcome.isSuccess();
    }

    /**
     * Whether the outcome says anything about the plugin.
     *
     * <p>A run that failed because the server could not start tells the user to fix their
     * settings, not their plugin.</p>
     *
     * @return {@code true} when the outcome is evidence about the plugin
     */
    public boolean isAttributableToPlugin() {
        return outcome.isAttributableToPlugin();
    }

    /**
     * Returns the issues at or above the given severity.
     *
     * @param threshold the minimum severity to include
     * @return the matching issues, in priority order
     */
    public List<VerificationIssue> issuesAtLeast(IssueSeverity threshold) {
        Objects.requireNonNull(threshold, "threshold");
        return issues.stream()
                .filter(issue -> issue.severity().ordinal() <= threshold.ordinal())
                .toList();
    }

    /** Whether any dependency was reported as missing. */
    public boolean hasMissingDependencies() {
        return !missingDependencies.isEmpty();
    }

    /** A one line summary suitable for a CLI. */
    public String summary() {
        String base = outcome.name() + ": " + outcome.description();
        if (hasMissingDependencies()) {
            return base + " (" + String.join(", ", missingDependencies) + ")";
        }
        return base;
    }
}
