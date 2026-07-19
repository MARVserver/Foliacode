package dev.marv.foliacode.transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The complete set of rewrites FoliaCode is willing to perform.
 *
 * <p>It is short, and that is the point. The analyzer knows about dozens of APIs
 * that break on Folia; this list covers the handful whose replacement can be
 * justified from the bytecode alone, without guessing at the author's intent. Every
 * other finding is reported as refused, with the reason.</p>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><b>{@code BukkitRunnable}.</b> Rewriting {@code new BukkitRunnable(){...}
 * .runTaskTimer(plugin, 0, 20)} looks like the same job as rewriting the scheduler
 * call, and it is not. A {@code BukkitRunnable} carries state — {@code cancel()} and
 * {@code getTaskId()} work because the runnable remembers the task Bukkit gave it.
 * A task handed to a Folia scheduler never gets one, so any later {@code cancel()}
 * would start throwing. Proving no such call exists means proving where the instance
 * can reach, including from inside its own {@code run()} method. That is escape
 * analysis, and getting it subtly wrong produces a plugin that runs and then
 * misbehaves — the exact outcome this tool exists to prevent.</p>
 *
 * <p><b>Block, world and inventory writes.</b> These need the write to happen on the
 * region that owns a particular location, which means wrapping the call in a
 * scheduler callback and moving the surrounding code with it. Where that boundary
 * lies is a question about what the code means, not about what it does.</p>
 */
public final class TransformRules {

    private static final String SCHEDULER = "org/bukkit/scheduler/BukkitScheduler";
    private static final String ENTITY = "org/bukkit/entity/Entity";
    private static final String SCHEDULER_SHIM = "dev/marv/foliacode/shim/FoliaSchedulers";
    private static final String ENTITY_SHIM = "dev/marv/foliacode/shim/FoliaEntities";

    /** Internal-name prefix of the classes copied into a transformed JAR. */
    public static final String SHIM_PACKAGE = "dev/marv/foliacode/shim/";

    /** The shim classes a transformed JAR needs. */
    public static final List<String> SHIM_CLASSES = List.of(
            "dev/marv/foliacode/shim/FoliaBridge",
            "dev/marv/foliacode/shim/FoliaSchedulers",
            "dev/marv/foliacode/shim/FoliaEntities");

    private static final String TICK_CAVEAT =
            "Folia rejects a delay or period below one tick, so a zero is raised to one. "
                    + "The task runs one tick later than it did on Bukkit.";

    private final List<TransformRule> rules;

    /** Builds a set with the default rules. */
    public TransformRules() {
        this(defaultRules());
    }

    /**
     * Builds a set with custom rules (used by tests).
     *
     * @param rules the rules
     */
    public TransformRules(List<TransformRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** Every rewrite in this set. */
    public List<TransformRule> rules() {
        return rules;
    }

    /** How many rewrites are available. */
    public int size() {
        return rules.size();
    }

    /**
     * Finds the rewrite for a call, if there is one.
     *
     * <p>The owner is matched exactly rather than through the type hierarchy for
     * everything except the declared owner itself; subtype resolution happens in
     * {@link CallSiteRewriter}, which has the hierarchy at hand.</p>
     *
     * @param name       the method name
     * @param descriptor the method descriptor
     * @return the matching rules, in declaration order
     */
    public List<TransformRule> candidatesFor(String name, String descriptor) {
        List<TransformRule> matches = new ArrayList<>(1);
        for (TransformRule rule : rules) {
            if (rule.methodName().equals(name) && rule.descriptor().equals(descriptor)) {
                matches.add(rule);
            }
        }
        return matches;
    }

    /**
     * Finds a rewrite by the exact method it replaces.
     *
     * @param owner      internal name of the declaring type
     * @param name       the method name
     * @param descriptor the method descriptor
     * @return the rule, if present
     */
    public Optional<TransformRule> find(String owner, String name, String descriptor) {
        return rules.stream()
                .filter(r -> r.owner().equals(owner)
                        && r.methodName().equals(name)
                        && r.descriptor().equals(descriptor))
                .findFirst();
    }

    /**
     * The default rewrites.
     *
     * @return the rules
     */
    public static List<TransformRule> defaultRules() {
        List<TransformRule> rules = new ArrayList<>();

        rules.add(new TransformRule(
                SCHEDULER, "runTask",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;",
                SCHEDULER_SHIM, "runTask",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/Object;",
                "Runs the task on the global region scheduler instead of the "
                        + "synchronous Bukkit scheduler, which Folia does not implement",
                null));

        rules.add(new TransformRule(
                SCHEDULER, "runTaskLater",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)Lorg/bukkit/scheduler/BukkitTask;",
                SCHEDULER_SHIM, "runTaskLater",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;J)Ljava/lang/Object;",
                "Runs the delayed task on the global region scheduler",
                TICK_CAVEAT));

        rules.add(new TransformRule(
                SCHEDULER, "runTaskTimer",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)Lorg/bukkit/scheduler/BukkitTask;",
                SCHEDULER_SHIM, "runTaskTimer",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;JJ)Ljava/lang/Object;",
                "Runs the repeating task on the global region scheduler",
                TICK_CAVEAT));

        // The older spelling of the same two calls. Real plugins are full of them —
        // EssentialsX reaches for scheduleSyncDelayedTask where newer code would write
        // runTaskLater — and leaving them out would refuse a call this tool can already
        // translate, purely because of its name.
        rules.add(new TransformRule(
                SCHEDULER, "scheduleSyncDelayedTask",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)I",
                SCHEDULER_SHIM, "scheduleSyncDelayedTask",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;J)I",
                "Runs the delayed task on the global region scheduler",
                TICK_CAVEAT));

        rules.add(new TransformRule(
                SCHEDULER, "scheduleSyncDelayedTask",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)I",
                SCHEDULER_SHIM, "scheduleSyncDelayedTask",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)I",
                "Runs the task on the global region scheduler",
                null));

        rules.add(new TransformRule(
                SCHEDULER, "scheduleSyncRepeatingTask",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)I",
                SCHEDULER_SHIM, "scheduleSyncRepeatingTask",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;JJ)I",
                "Runs the repeating task on the global region scheduler",
                TICK_CAVEAT));

        rules.add(new TransformRule(
                SCHEDULER, "runTaskAsynchronously",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;",
                SCHEDULER_SHIM, "runTaskAsynchronously",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/Object;",
                "Moves the task to Folia's async scheduler",
                "Only the scheduling changes. If the task body touches a world or an "
                        + "entity it was unsafe before and still is; the analyzer will keep "
                        + "reporting it."));

        rules.add(new TransformRule(
                ENTITY, "teleport", "(Lorg/bukkit/Location;)Z",
                ENTITY_SHIM, "teleport", "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                "Starts an asynchronous teleport instead of a synchronous one",
                "The teleport completes later. Code that ran after this call now runs "
                        + "before the entity has moved."));

        return Collections.unmodifiableList(rules);
    }
}
