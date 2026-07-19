package dev.marv.foliacode.agent;

import java.util.List;
import java.util.Objects;

/**
 * What one call site did while the server was running.
 *
 * @param site           the instrumented call site
 * @param executionCount how many times it ran in total
 * @param threads        the breakdown by thread, most frequent first
 */
public record SiteObservation(CallSite site, long executionCount, List<ThreadObservation> threads) {

    public SiteObservation {
        Objects.requireNonNull(site, "site");
        threads = List.copyOf(Objects.requireNonNull(threads, "threads"));
    }

    /** Whether the site executed at all. */
    public boolean executed() {
        return executionCount > 0;
    }

    /**
     * Whether the site ran somewhere the server did not call a tick thread.
     *
     * <p>This is a signal to look, not a defect on its own. Plenty of API is
     * perfectly legal off a tick thread — and a call on a tick thread can still be
     * on the wrong <em>region's</em> tick thread, which this cannot see. It narrows
     * the search; it does not close it.</p>
     */
    public boolean ranOffTickThread() {
        return threads.stream()
                .anyMatch(t -> t.verdict() == ThreadVerdict.OFF_TICK_THREAD && t.count() > 0);
    }
}
