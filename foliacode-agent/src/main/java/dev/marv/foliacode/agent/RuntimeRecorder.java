package dev.marv.foliacode.agent;

import dev.marv.foliacode.model.UnsafeApi;
import dev.marv.foliacode.probe.Probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps track of what each instrumented call site <em>is</em>.
 *
 * <p>The counting itself happens in {@link Probe}, which is published to the bootstrap
 * class loader so that instrumented plugin code can reach it on a server like Paper.
 * The split is deliberate: only the counter has to be visible server-wide, and
 * everything published there is visible to every plugin, so it is kept to the minimum.
 * The descriptions of the call sites — which rule matched, which line, which class —
 * stay here, in the system loader, where they can use FoliaCode's own model.</p>
 */
public final class RuntimeRecorder {

    private static final Map<Integer, CallSite> sites = new ConcurrentHashMap<>();

    private RuntimeRecorder() {
    }

    /**
     * Registers a call site and returns the identifier the woven code will pass back.
     *
     * <p>Called by the transformer while a class is being instrumented, never at
     * runtime.</p>
     *
     * @param className        declaring class (dot-separated)
     * @param methodName       declaring method
     * @param methodDescriptor descriptor of the declaring method
     * @param lineNumber       line number, or {@code -1} if unknown
     * @param calleeOwner      internal name of the class actually called
     * @param api              the rule this site matched
     * @return the site identifier
     */
    public static int register(
            String className,
            String methodName,
            String methodDescriptor,
            int lineNumber,
            String calleeOwner,
            UnsafeApi api
    ) {
        int id = Probe.newSiteId();
        sites.put(id, new CallSite(id, className, methodName, methodDescriptor,
                lineNumber, calleeOwner, api));
        return id;
    }

    /** How many call sites have been instrumented. */
    public static int instrumentedSiteCount() {
        return sites.size();
    }

    /**
     * Takes a snapshot of everything observed so far.
     *
     * <p>Sites that never ran are included with a count of zero. They are the more
     * interesting half of the result: a static finding that never executes is a
     * different problem from one that executes on the wrong thread.</p>
     *
     * @return the observations, most-executed first
     */
    public static List<SiteObservation> snapshot() {
        List<SiteObservation> observations = new ArrayList<>(sites.size());
        for (Map.Entry<Integer, CallSite> entry : sites.entrySet()) {
            List<ThreadObservation> threads = Probe.observationsFor(entry.getKey()).stream()
                    .map(RuntimeRecorder::toThreadObservation)
                    .toList();
            long total = threads.stream().mapToLong(ThreadObservation::count).sum();
            observations.add(new SiteObservation(entry.getValue(), total, threads));
        }
        observations.sort(Comparator
                .comparingLong(SiteObservation::executionCount).reversed()
                .thenComparing(o -> o.site().className())
                .thenComparingInt(o -> o.site().id()));
        return observations;
    }

    /**
     * Converts a probe observation into the reporting model.
     *
     * <p>The probe reports its verdict as a string because it may not share a class
     * loader with this enum. An unrecognised value becomes {@link ThreadVerdict#UNKNOWN}
     * rather than an exception: a mismatch between the two halves is a bug in FoliaCode,
     * and it should cost a thread label, not the whole report.</p>
     *
     * @param observation what the probe recorded
     * @return the reporting form
     */
    private static ThreadObservation toThreadObservation(Probe.Observation observation) {
        ThreadVerdict verdict;
        try {
            verdict = ThreadVerdict.valueOf(observation.verdict());
        } catch (IllegalArgumentException e) {
            verdict = ThreadVerdict.UNKNOWN;
        }
        return new ThreadObservation(observation.threadName(), verdict, observation.count());
    }

    /** Clears all state. Used by tests. */
    static void reset() {
        sites.clear();
        Probe.reset();
    }
}
