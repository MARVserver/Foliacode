package dev.marv.foliacode.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal Bukkit API stub for tests.
 *
 * <p>Depending on paper-api would tie the tests to the network and to one
 * specific version. Detection only needs signatures and a type hierarchy, so
 * that is all this provides.</p>
 *
 * <p>The hierarchy defined here must stay in sync with the built-in knowledge
 * in {@link dev.marv.foliacode.rules.TypeHierarchy}.</p>
 */
public final class BukkitStubs {

    private BukkitStubs() {
    }

    /**
     * Returns the stub sources.
     *
     * @return fully-qualified class name to source
     */
    public static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("org.bukkit.Material", """
                package org.bukkit;
                public enum Material { STONE, DIRT, AIR }
                """);

        sources.put("org.bukkit.Location", """
                package org.bukkit;
                public class Location {
                    public Location(World world, double x, double y, double z) { }
                }
                """);

        sources.put("org.bukkit.World", """
                package org.bukkit;
                import org.bukkit.block.Block;
                import org.bukkit.entity.Entity;
                public interface World {
                    Block getBlockAt(int x, int y, int z);
                    Object getChunkAt(int x, int z);
                    Entity spawnEntity(Location location, String type);
                }
                """);

        sources.put("org.bukkit.block.Block", """
                package org.bukkit.block;
                import org.bukkit.Material;
                public interface Block {
                    void setType(Material material);
                    boolean breakNaturally();
                }
                """);

        sources.put("org.bukkit.entity.Entity", """
                package org.bukkit.entity;
                import org.bukkit.Location;
                public interface Entity {
                    boolean teleport(Location location);
                    void remove();
                }
                """);

        sources.put("org.bukkit.entity.LivingEntity", """
                package org.bukkit.entity;
                public interface LivingEntity extends Entity {
                    void setHealth(double health);
                }
                """);

        sources.put("org.bukkit.entity.HumanEntity", """
                package org.bukkit.entity;
                public interface HumanEntity extends LivingEntity { }
                """);

        sources.put("org.bukkit.entity.Player", """
                package org.bukkit.entity;
                public interface Player extends HumanEntity { }
                """);

        sources.put("org.bukkit.plugin.Plugin", """
                package org.bukkit.plugin;
                public interface Plugin { }
                """);

        sources.put("org.bukkit.scheduler.BukkitTask", """
                package org.bukkit.scheduler;
                public interface BukkitTask {
                    int getTaskId();
                    void cancel();
                }
                """);

        // Return types match the real API. The transformer matches descriptors exactly,
        // so a stub that returned void would let a rewrite pass here and fail on a real
        // plugin — precisely the gap these tests exist to close.
        sources.put("org.bukkit.scheduler.BukkitScheduler", """
                package org.bukkit.scheduler;
                import org.bukkit.plugin.Plugin;
                public interface BukkitScheduler {
                    BukkitTask runTask(Plugin plugin, Runnable task);
                    BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay);
                    BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period);
                    BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task);
                }
                """);

        sources.put("org.bukkit.scheduler.BukkitRunnable", """
                package org.bukkit.scheduler;
                import org.bukkit.plugin.Plugin;
                public abstract class BukkitRunnable implements Runnable {
                    public BukkitTask runTaskTimer(Plugin plugin, long delay, long period) { return null; }
                    public BukkitTask runTask(Plugin plugin) { return null; }
                    public void cancel() { }
                }
                """);

        sources.put("org.bukkit.Bukkit", """
                package org.bukkit;
                import org.bukkit.scheduler.BukkitScheduler;
                public final class Bukkit {
                    public static BukkitScheduler getScheduler() { return null; }
                    public static World createWorld(Object creator) { return null; }
                }
                """);

        return sources;
    }

    /**
     * Returns the stub sources plus one class under test.
     *
     * @param className fully-qualified name of the class to add
     * @param source    its source
     * @return the combined sources
     */
    public static Map<String, String> with(String className, String source) {
        Map<String, String> sources = sources();
        sources.put(className, source);
        return sources;
    }
}
