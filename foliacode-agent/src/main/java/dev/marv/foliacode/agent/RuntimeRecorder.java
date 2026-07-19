package dev.marv.foliacode.agent;

import dev.marv.foliacode.model.UnsafeApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects what actually happened at the instrumented call sites.
 *
 * <p>Instrumented plugin code calls {@link #observe(int)} directly, so this class
 * is on the hot path of whatever the plugin does. Two rules follow from that:
 * counting must be cheap enough to disappear into the noise, and nothing thrown
 * here may ever escape into the plugin. An observer that changes the behaviour it
 * is observing produces a report about a program that does not exist.</p>
 *
 * <p>State is static because the injected bytecode has nowhere to keep an instance
 * reference: a plain {@code invokestatic} is the smallest, fastest thing that can
 * be woven into a call site.</p>
 */
public final class RuntimeRecorder {

    /**
     * How many distinct thread names to keep per site.
     *
     * <p>Thread names are unbounded — a plugin with its own thread pool can produce
     * a fresh name for every task. Without a cap, the memory used to describe a leak
     * would grow like the leak.</p>
     */
    private static final int MAX_THREADS_PER_SITE = 16;

    /** Stand-in name used once a site has exceeded {@link #MAX_THREADS_PER_SITE}. */
    private static final String OVERFLOW_THREAD_NAME = "(other threads)";

    private static final Map<Integer, CallSite> sites = new ConcurrentHashMap<>();

    private static final Map<Integer, SiteCounter> counters = new ConcurrentHashMap<>();

    private static final AtomicInteger nextId = new AtomicInteger();

    private RuntimeRecorder() {
    }

    /**
     * Registers a call site and returns the identifier the injected code will pass back.
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
        int id = nextId.getAndIncrement();
        sites.put(id, new CallSite(id, className, methodName, methodDescriptor,
                lineNumber, calleeOwner, api));
        counters.put(id, new SiteCounter());
        return id;
    }

    /**
     * Records that an instrumented call site is about to execute.
     *
     * <p>This is the method the injected bytecode calls. It must not throw.</p>
     *
     * @param siteId the identifier handed out by {@link #register}
     */
    public static void observe(int siteId) {
        try {
            SiteCounter counter = counters.get(siteId);
            if (counter == null) {
                return;
            }
            counter.record(Thread.currentThread().getName(), ServerThreadOracle.current());
        } catch (Throwable t) {
            // Deliberately swallowing everything, including Errors. An exception escaping
            // from here would surface inside the plugin as a fault the plugin does not have.
        }
    }

    /** How many call sites have been instrumented. */
    public static int instrumentedSiteCount() {
        return sites.size();
    }

    /**
     * Takes a snapshot of everything observed so far.
     *
     * <p>Sites that never ran are included with a count of zero. They are the most
     * interesting half of the result: a static finding that never executes is a
     * different problem from one that executes on the wrong thread.</p>
     *
     * @return the observations, most-executed first
     */
    public static List<SiteObservation> snapshot() {
        List<SiteObservation> observations = new ArrayList<>(sites.size());
        for (Map.Entry<Integer, CallSite> entry : sites.entrySet()) {
            SiteCounter counter = counters.get(entry.getKey());
            List<ThreadObservation> threads =
                    counter == null ? List.of() : counter.snapshot();
            long total = threads.stream().mapToLong(ThreadObservation::count).sum();
            observations.add(new SiteObservation(entry.getValue(), total, threads));
        }
        observations.sort(Comparator
                .comparingLong(SiteObservation::executionCount).reversed()
                .thenComparing(o -> o.site().className())
                .thenComparingInt(o -> o.site().id()));
        return observations;
    }

    /** Clears all state. Used by tests. */
    static void reset() {
        sites.clear();
        counters.clear();
        nextId.set(0);
        ServerThreadOracle.reset();
    }

    /** Per-site tallies, broken down by the thread the call ran on. */
    private static final class SiteCounter {

        private final Map<ThreadKey, LongAdder> byThread = new ConcurrentHashMap<>();

        void record(String threadName, ThreadVerdict verdict) {
            ThreadKey key = new ThreadKey(threadName, verdict);
            LongAdder adder = byThread.get(key);
            if (adder == null) {
                if (byThread.size() >= MAX_THREADS_PER_SITE) {
                    key = new ThreadKey(OVERFLOW_THREAD_NAME, verdict);
                    adder = byThread.get(key);
                }
                if (adder == null) {
                    adder = byThread.computeIfAbsent(key, ignored -> new LongAdder());
                }
            }
            adder.increment();
        }

        List<ThreadObservation> snapshot() {
            List<ThreadObservation> result = new ArrayList<>(byThread.size());
            byThread.forEach((key, adder) ->
                    result.add(new ThreadObservation(key.name(), key.verdict(), adder.sum())));
            result.sort(Comparator.comparingLong(ThreadObservation::count).reversed());
            return result;
        }
    }

    /** A thread name paired with what the server said about it. */
    private record ThreadKey(String name, ThreadVerdict verdict) {
    }
}
