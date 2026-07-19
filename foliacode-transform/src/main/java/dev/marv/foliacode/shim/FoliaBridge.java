package dev.marv.foliacode.shim;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Reaches the Folia scheduler API without being compiled against it.
 *
 * <p>This class is copied into the JAR being transformed, so it runs inside the
 * plugin, on whatever server the user happens to be running. Three constraints
 * follow, and they shape everything here:</p>
 *
 * <ul>
 *   <li><b>No dependencies.</b> Only the JDK. Anything else would have to be shaded
 *       into every plugin this tool touches.</li>
 *   <li><b>No compile-time Bukkit.</b> FoliaCode deliberately has no server API on
 *       its classpath, so every call is reflective and resolved against whatever
 *       version is actually present.</li>
 *   <li><b>Degrade, never fail.</b> If the Folia scheduler is missing — an older
 *       Spigot, say — the rewritten call falls back to the original one. A plugin
 *       that FoliaCode has touched must not stop working on the server it already
 *       ran on.</li>
 * </ul>
 *
 * <p>Method handles are resolved against the <em>interfaces</em> Bukkit declares,
 * never against the server's implementation classes, which are not part of the API
 * and are frequently inaccessible.</p>
 */
public final class FoliaBridge {

    private static volatile boolean resolved;

    private static Class<?> pluginType;
    private static Method getGlobalRegionScheduler;
    private static Method globalRun;
    private static Method globalRunDelayed;
    private static Method globalRunAtFixedRate;
    private static Method getAsyncScheduler;
    private static Method asyncRunNow;
    private static Method entityTeleportAsync;

    private FoliaBridge() {
    }

    /**
     * Runs a task on the global region.
     *
     * @param apiHost an object supplied by the server, consulted only for its class loader
     * @param plugin  the owning plugin
     * @param task    the task
     * @return true if the task was handed to the Folia scheduler
     */
    public static boolean globalRun(Object apiHost, Object plugin, Runnable task) {
        resolve(apiHost);
        return invokeScheduler(getGlobalRegionScheduler, globalRun, plugin, task);
    }

    /**
     * Runs a task on the global region after a delay.
     *
     * @param apiHost    an object supplied by the server, consulted only for its class loader
     * @param plugin     the owning plugin
     * @param task       the task
     * @param delayTicks the delay in ticks
     * @return true if the task was handed to the Folia scheduler
     */
    public static boolean globalRunDelayed(
            Object apiHost, Object plugin, Runnable task, long delayTicks) {
        resolve(apiHost);
        return invokeScheduler(getGlobalRegionScheduler, globalRunDelayed,
                plugin, task, atLeastOneTick(delayTicks));
    }

    /**
     * Runs a repeating task on the global region.
     *
     * @param apiHost     an object supplied by the server, consulted only for its class loader
     * @param plugin      the owning plugin
     * @param task        the task
     * @param delayTicks  the initial delay in ticks
     * @param periodTicks the period in ticks
     * @return true if the task was handed to the Folia scheduler
     */
    public static boolean globalRunAtFixedRate(
            Object apiHost, Object plugin, Runnable task, long delayTicks, long periodTicks) {
        resolve(apiHost);
        return invokeScheduler(getGlobalRegionScheduler, globalRunAtFixedRate,
                plugin, task, atLeastOneTick(delayTicks), atLeastOneTick(periodTicks));
    }

    /**
     * Runs a task on the async scheduler.
     *
     * @param apiHost an object supplied by the server, consulted only for its class loader
     * @param plugin  the owning plugin
     * @param task    the task
     * @return true if the task was handed to the Folia scheduler
     */
    public static boolean asyncRunNow(Object apiHost, Object plugin, Runnable task) {
        resolve(apiHost);
        return invokeScheduler(getAsyncScheduler, asyncRunNow, plugin, task);
    }

    /**
     * Starts an asynchronous teleport.
     *
     * @param entity   the entity to move
     * @param location the destination
     * @return true if the asynchronous teleport was started
     */
    public static boolean teleportAsync(Object entity, Object location) {
        resolve(entity);
        Method method = entityTeleportAsync;
        if (method == null || entity == null) {
            return false;
        }
        try {
            method.invoke(entity, location);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Invokes the method the call site originally targeted.
     *
     * <p>The fallback path, taken when the server has no Folia scheduler. The
     * receiver is still on the stack at the rewritten call site precisely so this
     * remains possible: a transformed plugin keeps working on the server it came
     * from.</p>
     *
     * @param receiver      the original receiver
     * @param interfaceName binary name of the interface declaring the method
     * @param methodName    the method name
     * @param parameterTypes binary names of the parameter types; {@code long} is
     *                      understood as the primitive
     * @param arguments     the arguments
     * @return whatever the original method returned
     * @throws IllegalStateException if the original method cannot be called either
     */
    public static Object invokeOriginal(
            Object receiver,
            String interfaceName,
            String methodName,
            String[] parameterTypes,
            Object... arguments) {
        try {
            ClassLoader loader = loaderOf(receiver);
            Class<?>[] types = new Class<?>[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                types[i] = "long".equals(parameterTypes[i])
                        ? long.class
                        : Class.forName(parameterTypes[i], false, loader);
            }
            return Class.forName(interfaceName, false, loader).getMethod(methodName, types)
                    .invoke(receiver, arguments);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Neither the Folia scheduler nor the original method could be reached.
            // Returning quietly would drop a scheduled task and look like a plugin bug
            // forever, so this fails loudly and says who is responsible.
            throw new IllegalStateException(
                    "FoliaCode: rewritten call to " + interfaceName + "." + methodName
                            + " could not reach the Folia scheduler or the original method", e);
        }
    }

    /**
     * Folia rejects a delay or period below one tick, where the Bukkit scheduler
     * accepted zero and meant "next tick".
     *
     * <p>This is the one place the rewrite is not a pure translation. Raising a zero
     * to one tick keeps the call legal and preserves the intent; the transformer
     * reports it so the change is never silent.</p>
     *
     * @param ticks the requested value
     * @return the value Folia will accept
     */
    private static long atLeastOneTick(long ticks) {
        return Math.max(1L, ticks);
    }

    /**
     * Fetches a scheduler from Bukkit and invokes a method on it.
     *
     * @param accessor the static Bukkit method returning the scheduler
     * @param method   the scheduler method to call
     * @param plugin   the owning plugin
     * @param task     the task to wrap in a Consumer
     * @param extras   any trailing arguments
     * @return true if the call succeeded
     */
    private static boolean invokeScheduler(
            Method accessor, Method method, Object plugin, Runnable task, Object... extras) {
        if (accessor == null || method == null || plugin == null || task == null) {
            return false;
        }
        try {
            Object scheduler = accessor.invoke(null);
            if (scheduler == null) {
                return false;
            }
            // The scheduler takes Consumer<ScheduledTask>; the task never needed the
            // handle, or it would not have been a plain Runnable to begin with.
            Consumer<Object> consumer = ignored -> task.run();

            Object[] arguments = new Object[2 + extras.length];
            arguments[0] = plugin;
            arguments[1] = consumer;
            System.arraycopy(extras, 0, arguments, 2, extras.length);

            method.invoke(scheduler, arguments);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The class loader to resolve server types through.
     *
     * <p>Not this class's own loader. The shim is copied into the plugin, but a server
     * may hand out API objects from a loader the plugin's own cannot reach, and
     * {@code Class.forName(String)} would quietly look in the wrong place. An object
     * the server just gave us is by definition loaded somewhere that can see the API
     * it implements, so it is the reliable starting point.</p>
     *
     * @param apiHost an object obtained from the server; may be {@code null}
     * @return the loader to use
     */
    private static ClassLoader loaderOf(Object apiHost) {
        if (apiHost != null) {
            ClassLoader loader = apiHost.getClass().getClassLoader();
            if (loader != null) {
                return loader;
            }
        }
        return FoliaBridge.class.getClassLoader();
    }

    /**
     * Resolves everything once, on first use.
     *
     * <p>Lazily, because the shim is loaded with the plugin and the server may not be
     * far enough along to answer. Every lookup is independent: a server with the
     * region schedulers but no {@code teleportAsync} still gets the scheduler
     * rewrites.</p>
     *
     * @param apiHost an object from the server, used to find the right class loader
     */
    private static void resolve(Object apiHost) {
        if (resolved) {
            return;
        }
        synchronized (FoliaBridge.class) {
            if (resolved) {
                return;
            }
            resolved = true;
            ClassLoader loader = loaderOf(apiHost);
            try {
                pluginType = Class.forName("org.bukkit.plugin.Plugin", false, loader);
            } catch (ClassNotFoundException | RuntimeException e) {
                return;
            }
            resolveGlobalScheduler(loader);
            resolveAsyncScheduler(loader);
            resolveTeleportAsync(loader);
        }
    }

    /**
     * Looks up {@code Bukkit.getGlobalRegionScheduler()} and the methods on it.
     *
     * @param loader the loader to resolve through
     */
    private static void resolveGlobalScheduler(ClassLoader loader) {
        try {
            Method accessor = Class.forName("org.bukkit.Bukkit", false, loader)
                    .getMethod("getGlobalRegionScheduler");
            Class<?> type = accessor.getReturnType();
            globalRun = type.getMethod("run", pluginType, Consumer.class);
            globalRunDelayed = type.getMethod("runDelayed", pluginType, Consumer.class, long.class);
            globalRunAtFixedRate = type.getMethod(
                    "runAtFixedRate", pluginType, Consumer.class, long.class, long.class);
            getGlobalRegionScheduler = accessor;
        } catch (ReflectiveOperationException | RuntimeException e) {
            getGlobalRegionScheduler = null;
        }
    }

    /**
     * Looks up {@code Bukkit.getAsyncScheduler()} and the method on it.
     *
     * @param loader the loader to resolve through
     */
    private static void resolveAsyncScheduler(ClassLoader loader) {
        try {
            Method accessor = Class.forName("org.bukkit.Bukkit", false, loader)
                    .getMethod("getAsyncScheduler");
            asyncRunNow = accessor.getReturnType().getMethod("runNow", pluginType, Consumer.class);
            getAsyncScheduler = accessor;
        } catch (ReflectiveOperationException | RuntimeException e) {
            getAsyncScheduler = null;
        }
    }

    /**
     * Looks up {@code Entity.teleportAsync(Location)}.
     *
     * @param loader the loader to resolve through
     */
    private static void resolveTeleportAsync(ClassLoader loader) {
        try {
            entityTeleportAsync = Class.forName("org.bukkit.entity.Entity", false, loader)
                    .getMethod("teleportAsync",
                            Class.forName("org.bukkit.Location", false, loader));
        } catch (ReflectiveOperationException | RuntimeException e) {
            entityTeleportAsync = null;
        }
    }

    /** Drops the cached lookups. Used by tests. */
    static synchronized void resetForTesting() {
        resolved = false;
        pluginType = null;
        getGlobalRegionScheduler = null;
        globalRun = null;
        globalRunDelayed = null;
        globalRunAtFixedRate = null;
        getAsyncScheduler = null;
        asyncRunNow = null;
        entityTeleportAsync = null;
    }
}
