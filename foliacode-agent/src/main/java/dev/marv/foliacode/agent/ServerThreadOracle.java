package dev.marv.foliacode.agent;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks the running server whether the current thread is one of its tick threads.
 *
 * <p>Classifying threads by name would be guesswork, and guesswork dressed up as
 * evidence is the failure mode this whole tool exists to avoid. The server knows
 * the answer, so the server is asked — through reflection, because the agent is
 * compiled without any Bukkit on the classpath and has to run against whatever
 * version happens to be present.</p>
 *
 * <p>Resolution is lazy. The agent starts before Bukkit is loaded, so early calls
 * legitimately find nothing; failed lookups are throttled so that a server without
 * Bukkit at all does not pay for a {@link ClassNotFoundException} on every
 * observation.</p>
 */
final class ServerThreadOracle {

    /** How many observations to skip before retrying a failed lookup. */
    private static final int RETRY_INTERVAL = 512;

    private static volatile Method isPrimaryThread;

    private static final AtomicInteger observationsUntilRetry = new AtomicInteger();

    private ServerThreadOracle() {
    }

    /**
     * Classifies the calling thread.
     *
     * @return what the server said, or {@link ThreadVerdict#UNKNOWN} if it could not be asked
     */
    static ThreadVerdict current() {
        Method method = isPrimaryThread;
        if (method == null) {
            if (observationsUntilRetry.getAndDecrement() > 0) {
                return ThreadVerdict.UNKNOWN;
            }
            method = resolve();
            if (method == null) {
                observationsUntilRetry.set(RETRY_INTERVAL);
                return ThreadVerdict.UNKNOWN;
            }
        }
        try {
            Object result = method.invoke(null);
            if (result instanceof Boolean primary) {
                return primary ? ThreadVerdict.TICK_THREAD : ThreadVerdict.OFF_TICK_THREAD;
            }
            return ThreadVerdict.UNKNOWN;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return ThreadVerdict.UNKNOWN;
        }
    }

    /**
     * Attempts to find {@code Bukkit.isPrimaryThread()}.
     *
     * @return the method, or {@code null} if the server is not loaded
     */
    private static Method resolve() {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit", false,
                    ClassLoader.getSystemClassLoader());
            Method method = bukkit.getMethod("isPrimaryThread");
            isPrimaryThread = method;
            return method;
        } catch (ClassNotFoundException | NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /** Drops the cached lookup. Used by tests. */
    static void reset() {
        isPrimaryThread = null;
        observationsUntilRetry.set(0);
    }
}
