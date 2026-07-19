package dev.marv.foliacode.shim;

/**
 * The scheduler entry points rewritten call sites jump to.
 *
 * <p>Every method here mirrors the shape of the call it replaces: the original
 * receiver becomes the first parameter, the rest follow unchanged. That is what
 * makes the rewrite a single instruction swap — the operand stack at the call site
 * is already exactly right, so no shuffling is needed and the method's stack map
 * frames stay valid.</p>
 *
 * <p>Parameters are typed {@code Object} because this class is compiled without any
 * Bukkit on the classpath. Passing a {@code BukkitScheduler} where {@code Object} is
 * declared is a widening reference conversion, which the JVM verifier accepts
 * without a cast.</p>
 *
 * <p>Return types are {@code Object} rather than {@code BukkitTask}. The transformer
 * only rewrites call sites whose result is discarded, so nothing ever looks at the
 * value — and it could not honestly produce a {@code BukkitTask} anyway, because the
 * Folia schedulers do not hand one out.</p>
 */
public final class FoliaSchedulers {

    private static final String SCHEDULER = "org.bukkit.scheduler.BukkitScheduler";
    private static final String PLUGIN = "org.bukkit.plugin.Plugin";
    private static final String RUNNABLE = "java.lang.Runnable";

    private FoliaSchedulers() {
    }

    /**
     * Replaces {@code BukkitScheduler.runTask(Plugin, Runnable)}.
     *
     * @param scheduler the original scheduler
     * @param plugin    the owning plugin
     * @param task      the task
     * @return always {@code null}; the result of the original call was discarded
     */
    public static Object runTask(Object scheduler, Object plugin, Runnable task) {
        if (FoliaBridge.globalRun(scheduler, plugin, task)) {
            return null;
        }
        return FoliaBridge.invokeOriginal(scheduler, SCHEDULER, "runTask",
                new String[]{PLUGIN, RUNNABLE}, plugin, task);
    }

    /**
     * Replaces {@code BukkitScheduler.runTaskLater(Plugin, Runnable, long)}.
     *
     * @param scheduler the original scheduler
     * @param plugin    the owning plugin
     * @param task      the task
     * @param delay     the delay in ticks
     * @return always {@code null}; the result of the original call was discarded
     */
    public static Object runTaskLater(Object scheduler, Object plugin, Runnable task, long delay) {
        if (FoliaBridge.globalRunDelayed(scheduler, plugin, task, delay)) {
            return null;
        }
        return FoliaBridge.invokeOriginal(scheduler, SCHEDULER, "runTaskLater",
                new String[]{PLUGIN, RUNNABLE, "long"}, plugin, task, delay);
    }

    /**
     * Replaces {@code BukkitScheduler.runTaskTimer(Plugin, Runnable, long, long)}.
     *
     * @param scheduler the original scheduler
     * @param plugin    the owning plugin
     * @param task      the task
     * @param delay     the initial delay in ticks
     * @param period    the period in ticks
     * @return always {@code null}; the result of the original call was discarded
     */
    public static Object runTaskTimer(
            Object scheduler, Object plugin, Runnable task, long delay, long period) {
        if (FoliaBridge.globalRunAtFixedRate(scheduler, plugin, task, delay, period)) {
            return null;
        }
        return FoliaBridge.invokeOriginal(scheduler, SCHEDULER, "runTaskTimer",
                new String[]{PLUGIN, RUNNABLE, "long", "long"}, plugin, task, delay, period);
    }

    /**
     * Replaces {@code BukkitScheduler.scheduleSyncDelayedTask(Plugin, Runnable, long)}.
     *
     * <p>The older spelling of {@code runTaskLater}, and the same translation. It
     * returns an {@code int} task id rather than a {@code BukkitTask}; the Folia
     * schedulers issue neither, which is why the transformer requires the result to be
     * discarded before touching the call.</p>
     *
     * @param scheduler the original scheduler
     * @param plugin    the owning plugin
     * @param task      the task
     * @param delay     the delay in ticks
     * @return always {@code 0}; the result of the original call was discarded
     */
    public static int scheduleSyncDelayedTask(
            Object scheduler, Object plugin, Runnable task, long delay) {
        if (FoliaBridge.globalRunDelayed(scheduler, plugin, task, delay)) {
            return 0;
        }
        Object result = FoliaBridge.invokeOriginal(scheduler, SCHEDULER, "scheduleSyncDelayedTask",
                new String[]{PLUGIN, RUNNABLE, "long"}, plugin, task, delay);
        return result instanceof Integer id ? id : 0;
    }

    /**
     * Replaces {@code BukkitScheduler.scheduleSyncDelayedTask(Plugin, Runnable)}.
     *
     * <p>The no-delay overload, which Bukkit documents as "next tick" — the same thing
     * a one-tick delay means to Folia.</p>
     *
     * @param scheduler the original scheduler
     * @param plugin    the owning plugin
     * @param task      the task
     * @return always {@code 0}; the result of the original call was discarded
     */
    public static int scheduleSyncDelayedTask(Object scheduler, Object plugin, Runnable task) {
        if (FoliaBridge.globalRun(scheduler, plugin, task)) {
            return 0;
        }
        Object result = FoliaBridge.invokeOriginal(scheduler, SCHEDULER, "scheduleSyncDelayedTask",
                new String[]{PLUGIN, RUNNABLE}, plugin, task);
        return result instanceof Integer id ? id : 0;
    }

    /**
     * Replaces {@code BukkitScheduler.scheduleSyncRepeatingTask(Plugin, Runnable, long, long)}.
     *
     * @param scheduler the original scheduler
     * @param plugin    the owning plugin
     * @param task      the task
     * @param delay     the initial delay in ticks
     * @param period    the period in ticks
     * @return always {@code 0}; the result of the original call was discarded
     */
    public static int scheduleSyncRepeatingTask(
            Object scheduler, Object plugin, Runnable task, long delay, long period) {
        if (FoliaBridge.globalRunAtFixedRate(scheduler, plugin, task, delay, period)) {
            return 0;
        }
        Object result = FoliaBridge.invokeOriginal(scheduler, SCHEDULER, "scheduleSyncRepeatingTask",
                new String[]{PLUGIN, RUNNABLE, "long", "long"}, plugin, task, delay, period);
        return result instanceof Integer id ? id : 0;
    }

    /**
     * Replaces {@code BukkitScheduler.runTaskAsynchronously(Plugin, Runnable)}.
     *
     * <p>Bukkit's async pool is not the same as Folia's, and only the latter is
     * supported on Folia. The task body is untouched: if it reaches into a world or
     * an entity it was already unsafe, and moving it between pools does not fix
     * that. The analyzer still reports the body.</p>
     *
     * @param scheduler the original scheduler
     * @param plugin    the owning plugin
     * @param task      the task
     * @return always {@code null}; the result of the original call was discarded
     */
    public static Object runTaskAsynchronously(Object scheduler, Object plugin, Runnable task) {
        if (FoliaBridge.asyncRunNow(scheduler, plugin, task)) {
            return null;
        }
        return FoliaBridge.invokeOriginal(scheduler, SCHEDULER, "runTaskAsynchronously",
                new String[]{PLUGIN, RUNNABLE}, plugin, task);
    }
}
