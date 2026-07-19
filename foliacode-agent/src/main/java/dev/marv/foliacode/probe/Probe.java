package dev.marv.foliacode.probe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The counter that instrumented plugin code calls into.
 *
 * <p>This class exists as its own package for one reason: it is published to the
 * <em>bootstrap</em> class loader, and everything else in FoliaCode is not.</p>
 *
 * <p>A {@code -javaagent} JAR lands on the system class path, which is enough on a
 * plain JVM. Paper is not a plain JVM — it gives each plugin an isolated loader that
 * does not delegate arbitrary packages upwards, so a woven call to a class on the
 * system class path fails to link and kills the plugin with a
 * {@link NoClassDefFoundError}. The bootstrap loader is the one loader everything
 * else delegates to, so the woven target lives here.</p>
 *
 * <p>Two rules follow, and both are load-bearing:</p>
 *
 * <ul>
 *   <li><b>Nothing but the JDK.</b> No ASM, no FoliaCode model classes. Whatever is
 *       published to the bootstrap loader is visible to the entire server, and
 *       shadowing a library the server or a plugin depends on would break the server
 *       in order to observe it.</li>
 *   <li><b>Everything public.</b> The reporting half of the agent runs in the system
 *       loader and reads from here. Package-private access across loaders throws
 *       {@link IllegalAccessError} — which is exactly what a first attempt at this
 *       fix did, taking the whole JVM down with it.</li>
 * </ul>
 *
 * <p>{@link #observe(int)} sits on the hot path of whatever the plugin does, so it
 * has to be cheap, thread-safe, and incapable of throwing into plugin code.</p>
 */
public final class Probe {

    /**
     * How many distinct thread names to keep per site.
     *
     * <p>Thread names are unbounded — a plugin with its own pool can produce a fresh
     * one per task. Without a cap, the memory used to describe a leak would grow like
     * the leak.</p>
     */
    private static final int MAX_THREADS_PER_SITE = 16;

    /** Stand-in name used once a site has exceeded {@link #MAX_THREADS_PER_SITE}. */
    private static final String OVERFLOW_THREAD_NAME = "(other threads)";

    /** Verdict when the server said this is one of its tick threads. */
    public static final String TICK_THREAD = "TICK_THREAD";

    /** Verdict when the server said this is not a tick thread. */
    public static final String OFF_TICK_THREAD = "OFF_TICK_THREAD";

    /** Verdict when the server could not be asked. Never a guess. */
    public static final String UNKNOWN = "UNKNOWN";

    /** How many observations to skip before retrying a failed Bukkit lookup. */
    private static final int RETRY_INTERVAL = 512;

    private static final Map<Integer, Map<ThreadKey, LongAdder>> counts = new ConcurrentHashMap<>();

    private static final AtomicInteger nextId = new AtomicInteger();

    private static final AtomicInteger observationsUntilRetry = new AtomicInteger();

    private static volatile Method isPrimaryThread;

    private static volatile ClassLoader serverApiLoader;

    private Probe() {
    }

    /**
     * Allocates an identifier for a call site about to be instrumented.
     *
     * @return the identifier the woven code will pass to {@link #observe(int)}
     */
    public static int newSiteId() {
        int id = nextId.getAndIncrement();
        counts.put(id, new ConcurrentHashMap<>());
        return id;
    }

    /**
     * Records that an instrumented call site is about to execute.
     *
     * <p>The method the woven bytecode calls. It must not throw.</p>
     *
     * @param siteId the identifier from {@link #newSiteId()}
     */
    public static void observe(int siteId) {
        try {
            Map<ThreadKey, LongAdder> byThread = counts.get(siteId);
            if (byThread == null) {
                return;
            }
            ThreadKey key = new ThreadKey(Thread.currentThread().getName(), currentVerdict());
            LongAdder adder = byThread.get(key);
            if (adder == null) {
                if (byThread.size() >= MAX_THREADS_PER_SITE) {
                    key = new ThreadKey(OVERFLOW_THREAD_NAME, key.verdict());
                }
                adder = byThread.computeIfAbsent(key, ignored -> new LongAdder());
            }
            adder.increment();
        } catch (Throwable t) {
            // Deliberately swallowing everything, including Errors. An exception escaping
            // here would surface inside the plugin as a fault the plugin does not have.
        }
    }

    /** How many call sites have been registered. */
    public static int siteCount() {
        return counts.size();
    }

    /**
     * What one call site did, broken down by thread.
     *
     * @param siteId the site identifier
     * @return the observations, most frequent first
     */
    public static List<Observation> observationsFor(int siteId) {
        Map<ThreadKey, LongAdder> byThread = counts.get(siteId);
        if (byThread == null) {
            return List.of();
        }
        List<Observation> result = new ArrayList<>(byThread.size());
        byThread.forEach((key, adder) ->
                result.add(new Observation(key.name(), key.verdict(), adder.sum())));
        result.sort(Comparator.comparingLong(Observation::count).reversed());
        return result;
    }

    /**
     * Remembers a loader that can see the server API.
     *
     * <p>Paper keeps the server API off the system class path, so looking for
     * {@code org.bukkit.Bukkit} through the system loader finds nothing and every
     * thread would be reported as unknown. A loader that just supplied a class calling
     * Bukkit can, by construction, see Bukkit.</p>
     *
     * @param loader the defining loader of an instrumented class; may be {@code null}
     */
    public static void rememberLoader(ClassLoader loader) {
        if (loader != null && serverApiLoader == null) {
            serverApiLoader = loader;
        }
    }

    /** Clears all state. Used by tests. */
    public static void reset() {
        counts.clear();
        nextId.set(0);
        isPrimaryThread = null;
        serverApiLoader = null;
        observationsUntilRetry.set(0);
    }

    /**
     * Asks the server whether the calling thread is one of its tick threads.
     *
     * <p>Classifying threads by name would be guesswork, and guesswork presented as
     * evidence is the failure this tool exists to avoid. Resolution is lazy because
     * the agent starts before Bukkit is loaded, and failed lookups are throttled so a
     * server without Bukkit does not pay for an exception on every observation.</p>
     *
     * @return one of {@link #TICK_THREAD}, {@link #OFF_TICK_THREAD}, {@link #UNKNOWN}
     */
    private static String currentVerdict() {
        Method method = isPrimaryThread;
        if (method == null) {
            if (observationsUntilRetry.getAndDecrement() > 0) {
                return UNKNOWN;
            }
            method = resolve();
            if (method == null) {
                observationsUntilRetry.set(RETRY_INTERVAL);
                return UNKNOWN;
            }
        }
        try {
            Object result = method.invoke(null);
            if (result instanceof Boolean primary) {
                return primary ? TICK_THREAD : OFF_TICK_THREAD;
            }
            return UNKNOWN;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    /**
     * Attempts to find {@code Bukkit.isPrimaryThread()}.
     *
     * @return the method, or {@code null} if no loader can see the server
     */
    private static Method resolve() {
        ClassLoader plugin = serverApiLoader;
        if (plugin != null) {
            Method found = resolveVia(plugin);
            if (found != null) {
                return found;
            }
        }
        return resolveVia(ClassLoader.getSystemClassLoader());
    }

    /**
     * Attempts the lookup through one loader.
     *
     * @param loader the loader to try
     * @return the method, or {@code null} if this loader cannot see Bukkit
     */
    private static Method resolveVia(ClassLoader loader) {
        try {
            Method method = Class.forName("org.bukkit.Bukkit", false, loader)
                    .getMethod("isPrimaryThread");
            isPrimaryThread = method;
            return method;
        } catch (ClassNotFoundException | NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /**
     * How often one call site ran on one thread.
     *
     * <p>The raw thread name is kept next to the verdict on purpose: the verdict is
     * what the tool concluded, the name is the evidence a human can check it
     * against.</p>
     *
     * @param threadName name of the thread as reported by the JVM
     * @param verdict    what the server said; one of the constants on this class
     * @param count      how many times the site ran there
     */
    public record Observation(String threadName, String verdict, long count) {
    }

    /** A thread name paired with the verdict recorded for it. */
    private record ThreadKey(String name, String verdict) {
    }
}
