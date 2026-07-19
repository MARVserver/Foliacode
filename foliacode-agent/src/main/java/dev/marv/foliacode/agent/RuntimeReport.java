package dev.marv.foliacode.agent;

import dev.marv.foliacode.model.Severity;

import java.util.List;
import java.util.Objects;

/**
 * What the agent saw over one server run.
 *
 * <p>The value of this report is the contrast with the static one. Static analysis
 * lists call sites that <em>could</em> run; this lists the ones that <em>did</em>,
 * and says which thread they ran on. A site that never executed is not proof it is
 * harmless — it may simply not have been exercised — and the report says so rather
 * than letting silence read as an all-clear.</p>
 *
 * @param instrumentedClasses  how many classes were rewritten
 * @param methodReferenceSites unsafe method references that could not be instrumented
 * @param observations         one entry per instrumented call site
 */
public record RuntimeReport(
        int instrumentedClasses,
        int methodReferenceSites,
        List<SiteObservation> observations
) {

    public RuntimeReport {
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
    }

    /** How many call sites were instrumented. */
    public int instrumentedSites() {
        return observations.size();
    }

    /** The sites that actually executed. */
    public List<SiteObservation> executed() {
        return observations.stream().filter(SiteObservation::executed).toList();
    }

    /** The sites that were instrumented but never ran. */
    public List<SiteObservation> neverExecuted() {
        return observations.stream().filter(o -> !o.executed()).toList();
    }

    /**
     * Executed sites that ran off a tick thread, worst severity first.
     *
     * <p>This is the shortlist worth a human's attention: the rule set says the API
     * is thread-sensitive, and the server confirmed it ran somewhere that is not a
     * tick thread.</p>
     */
    public List<SiteObservation> offTickThreadExecutions() {
        return observations.stream()
                .filter(SiteObservation::executed)
                .filter(SiteObservation::ranOffTickThread)
                .sorted((a, b) -> {
                    int bySeverity = Integer.compare(
                            a.site().api().severity().ordinal(),
                            b.site().api().severity().ordinal());
                    return bySeverity != 0
                            ? bySeverity
                            : Long.compare(b.executionCount(), a.executionCount());
                })
                .toList();
    }

    /**
     * Counts executed sites at one severity.
     *
     * @param severity the severity to count
     * @return the number of distinct sites that ran
     */
    public long executedCount(Severity severity) {
        return observations.stream()
                .filter(SiteObservation::executed)
                .filter(o -> o.site().api().severity() == severity)
                .count();
    }
}
