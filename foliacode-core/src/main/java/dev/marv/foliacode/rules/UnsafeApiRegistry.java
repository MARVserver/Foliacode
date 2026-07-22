package dev.marv.foliacode.rules;

import dev.marv.foliacode.model.Category;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.UnsafeApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Knowledge base of APIs that break on Folia.
 *
 * <p>Every rule lives here as data. pasta spread its matching strings across
 * the individual transformers, which made it impossible to see which APIs
 * were covered. Collecting them in one place turns coverage into a list
 * somebody can actually review.</p>
 *
 * <p>Severity reflects what Folia actually does at runtime:
 * <ul>
 *   <li>{@link Severity#CRITICAL} — throws, so the code cannot work</li>
 *   <li>{@link Severity#HIGH} — thread-safety violation when called from another region</li>
 *   <li>{@link Severity#MEDIUM} — depends on the calling context</li>
 *   <li>{@link Severity#INFO} — a notice that static analysis cannot decide</li>
 * </ul>
 * </p>
 */
public final class UnsafeApiRegistry {

    private static final String SCHEDULER = "org/bukkit/scheduler/BukkitScheduler";
    private static final String RUNNABLE = "org/bukkit/scheduler/BukkitRunnable";
    private static final String BUKKIT = "org/bukkit/Bukkit";
    private static final String SERVER = "org/bukkit/Server";
    private static final String BLOCK = "org/bukkit/block/Block";
    private static final String BLOCK_STATE = "org/bukkit/block/BlockState";
    private static final String ENTITY = "org/bukkit/entity/Entity";
    private static final String LIVING_ENTITY = "org/bukkit/entity/LivingEntity";
    private static final String HUMAN_ENTITY = "org/bukkit/entity/HumanEntity";
    private static final String PLAYER = "org/bukkit/entity/Player";
    private static final String WORLD = "org/bukkit/World";
    private static final String CHUNK = "org/bukkit/Chunk";
    private static final String INVENTORY = "org/bukkit/inventory/Inventory";
    private static final String WORLD_CREATOR = "org/bukkit/WorldCreator";
    private static final String PLUGIN = "org/bukkit/plugin/Plugin";

    private final List<UnsafeApi> rules;

    /** Method name to matching rules; an index for narrowing by name first. */
    private final Map<String, List<UnsafeApi>> byMethodName;

    /** Rules that match regardless of method name. */
    private final List<UnsafeApi> wildcardRules;

    /** Builds a registry with the default rule set. */
    public UnsafeApiRegistry() {
        this(defaultRules());
    }

    /**
     * Builds a registry with a custom rule set (used by tests).
     *
     * @param rules the rules to use
     */
    public UnsafeApiRegistry(List<UnsafeApi> rules) {
        this.rules = List.copyOf(rules);
        this.byMethodName = new HashMap<>();
        List<UnsafeApi> wildcards = new ArrayList<>();
        for (UnsafeApi rule : this.rules) {
            if (UnsafeApi.ANY_METHOD.equals(rule.methodName())) {
                wildcards.add(rule);
            } else {
                byMethodName.computeIfAbsent(rule.methodName(), key -> new ArrayList<>()).add(rule);
            }
        }
        this.wildcardRules = List.copyOf(wildcards);
    }

    /** Every registered rule. */
    public List<UnsafeApi> rules() {
        return rules;
    }

    /** Number of registered rules. */
    public int size() {
        return rules.size();
    }

    /**
     * Tests whether a call matches any rule.
     *
     * <p>Owners are matched through the type hierarchy, so a call to
     * {@code Player#teleport} still matches the {@code Entity.teleport}
     * rule.</p>
     *
     * <p>When several rules match, the most serious one wins.</p>
     *
     * @param owner     internal name of the called class
     * @param name      name of the called method
     * @param desc      descriptor of the called method
     * @param hierarchy the type hierarchy
     * @return the matching rule, or empty if none matched
     */
    public Optional<UnsafeApi> match(String owner, String name, String desc, TypeHierarchy hierarchy) {
        if (owner == null || name == null) {
            return Optional.empty();
        }
        List<UnsafeApi> named = byMethodName.get(name);
        if (named == null && wildcardRules.isEmpty()) {
            return Optional.empty();
        }

        UnsafeApi best = null;
        if (named != null) {
            best = pickBest(best, named, owner, desc, hierarchy);
        }
        best = pickBest(best, wildcardRules, owner, desc, hierarchy);
        return Optional.ofNullable(best);
    }

    /**
     * Picks the most serious matching rule out of a set of candidates.
     *
     * @param current   best candidate so far, or {@code null} if there is none
     * @param candidates candidates to examine
     * @param owner     internal name of the called class
     * @param desc      descriptor of the called method
     * @param hierarchy the type hierarchy
     * @return the updated best candidate
     */
    private UnsafeApi pickBest(
            UnsafeApi current,
            List<UnsafeApi> candidates,
            String owner,
            String desc,
            TypeHierarchy hierarchy
    ) {
        UnsafeApi best = current;
        for (UnsafeApi rule : candidates) {
            if (!rule.matchesDescriptor(desc)) {
                continue;
            }
            if (!hierarchy.isSubtypeOf(owner, rule.owner())) {
                continue;
            }
            if (best == null || rule.severity().ordinal() < best.severity().ordinal()) {
                best = rule;
            }
        }
        return best;
    }

    /**
     * The default rule set.
     *
     * @return the rules
     */
    public static List<UnsafeApi> defaultRules() {
        List<UnsafeApi> rules = new ArrayList<>();

        // ================================================================
        // Scheduler — Folia throws on the synchronous BukkitScheduler methods
        // ================================================================
        String schedulerReason =
                "Folia does not implement synchronous scheduling on BukkitScheduler; "
                        + "these methods throw UnsupportedOperationException";
        String schedulerRemedy =
                "Switch to GlobalRegionScheduler, RegionScheduler, or EntityScheduler, "
                        + "depending on what the task touches";

        for (String method : List.of("runTask", "runTaskLater", "runTaskTimer",
                "scheduleSyncDelayedTask", "scheduleSyncRepeatingTask", "callSyncMethod")) {
            rules.add(new UnsafeApi(SCHEDULER, method, null, Category.SCHEDULER,
                    Severity.CRITICAL, schedulerReason, schedulerRemedy));
        }
        for (String method : List.of("runTask", "runTaskLater", "runTaskTimer")) {
            rules.add(new UnsafeApi(RUNNABLE, method, null, Category.SCHEDULER,
                    Severity.CRITICAL, schedulerReason, schedulerRemedy));
        }

        String asyncReason =
                "Async scheduling itself works on Folia, but touching worlds or entities "
                        + "from inside the task is a thread-safety violation";
        String asyncRemedy =
                "Move the task to AsyncScheduler and hand any world or entity access "
                        + "off to RegionScheduler";
        for (String method : List.of("runTaskAsynchronously", "runTaskLaterAsynchronously",
                "runTaskTimerAsynchronously", "scheduleAsyncDelayedTask", "scheduleAsyncRepeatingTask")) {
            rules.add(new UnsafeApi(SCHEDULER, method, null, Category.SCHEDULER,
                    Severity.MEDIUM, asyncReason, asyncRemedy));
        }
        for (String method : List.of("runTaskAsynchronously", "runTaskLaterAsynchronously",
                "runTaskTimerAsynchronously")) {
            rules.add(new UnsafeApi(RUNNABLE, method, null, Category.SCHEDULER,
                    Severity.MEDIUM, asyncReason, asyncRemedy));
        }

        // ================================================================
        // World creation and management — Folia has no dynamic world support
        // ================================================================
        // Both owners matter: plugins reach these through the Bukkit facade
        // (Bukkit.createWorld) and through the Server interface
        // (getServer().createWorld). Covering only the facade would let every
        // getServer()-routed call slip through, which is the more common spelling.
        for (String serverOwner : List.of(BUKKIT, SERVER)) {
            rules.add(new UnsafeApi(serverOwner, "createWorld", null, Category.WORLDGEN,
                    Severity.CRITICAL,
                    "Folia cannot create worlds at runtime",
                    "Create the world before startup and look it up instead"));
            rules.add(new UnsafeApi(serverOwner, "unloadWorld", null, Category.WORLDGEN,
                    Severity.CRITICAL,
                    "Folia cannot unload worlds at runtime",
                    "Manage world lifecycle through server configuration rather than from the plugin"));
        }
        rules.add(new UnsafeApi(WORLD_CREATOR, UnsafeApi.ANY_METHOD, null, Category.WORLDGEN,
                Severity.MEDIUM,
                "WorldCreator exists to build worlds at runtime, which Folia does not support",
                "Use worlds that already exist at startup"));
        rules.add(new UnsafeApi(PLUGIN, "getDefaultWorldGenerator", null, Category.WORLDGEN,
                Severity.HIGH,
                "Folia drives custom generators from several region threads at once, "
                        + "so the generator must be thread-safe",
                "Audit your ChunkGenerator for shared mutable state"));

        // ================================================================
        // Blocks — writing outside the owning region violates thread safety
        // ================================================================
        String blockReason =
                "Writing to a block is only allowed from the thread that owns that block's region";
        String blockRemedy =
                "Perform the write inside RegionScheduler.execute(plugin, location, ...)";
        for (String method : List.of("setType", "setBlockData", "breakNaturally", "applyBoneMeal")) {
            rules.add(new UnsafeApi(BLOCK, method, null, Category.BLOCK,
                    Severity.HIGH, blockReason, blockRemedy));
        }
        rules.add(new UnsafeApi(BLOCK_STATE, "update", null, Category.BLOCK,
                Severity.HIGH, blockReason, blockRemedy));

        // ================================================================
        // Entities
        // ================================================================
        rules.add(new UnsafeApi(ENTITY, "teleport", null, Category.ENTITY,
                Severity.HIGH,
                "A synchronous teleport cannot be carried out safely when the source and "
                        + "destination belong to different regions",
                "Use teleportAsync(Location) and chain follow-up work onto the returned "
                        + "CompletableFuture instead of blocking on it"));
        rules.add(new UnsafeApi(ENTITY, "remove", null, Category.ENTITY,
                Severity.HIGH,
                "Removing an entity is only allowed from the region that owns it",
                "Call entity.getScheduler().run(plugin, task -> entity.remove(), null)"));
        rules.add(new UnsafeApi(ENTITY, "getNearbyEntities", null, Category.ENTITY,
                Severity.HIGH,
                "A nearby-entity lookup can reach across more than one region",
                "Keep the search inside a single region, or split the query per region"));
        rules.add(new UnsafeApi(LIVING_ENTITY, "damage", null, Category.ENTITY,
                Severity.HIGH,
                "Damaging an entity is only allowed from the region that owns it",
                "Run it through that entity's EntityScheduler"));
        rules.add(new UnsafeApi(LIVING_ENTITY, "setHealth", null, Category.ENTITY,
                Severity.HIGH,
                "Changing an entity's health is only allowed from the region that owns it",
                "Run it through that entity's EntityScheduler"));
        rules.add(new UnsafeApi(ENTITY, "setVelocity", null, Category.ENTITY,
                Severity.MEDIUM,
                "Changing an entity's velocity is only allowed from the region that owns it",
                "Run it through that entity's EntityScheduler"));

        // The rest of the region-owned entity mutations. They share one rule with
        // teleport/remove/damage: an entity may only be changed from the region that
        // currently owns it, so a call from anywhere else is a thread-safety violation.
        String entityMutationReason =
                "Mutating an entity is only allowed from the region that currently owns it";
        String entityMutationRemedy =
                "Run it through that entity's EntityScheduler: "
                        + "entity.getScheduler().run(plugin, task -> ..., null)";
        for (String method : List.of("setFireTicks", "addPassenger", "removePassenger",
                "leaveVehicle")) {
            rules.add(new UnsafeApi(ENTITY, method, null, Category.ENTITY,
                    Severity.HIGH, entityMutationReason, entityMutationRemedy));
        }
        for (String method : List.of("addPotionEffect", "removePotionEffect")) {
            rules.add(new UnsafeApi(LIVING_ENTITY, method, null, Category.ENTITY,
                    Severity.HIGH, entityMutationReason, entityMutationRemedy));
        }
        rules.add(new UnsafeApi(PLAYER, "kickPlayer", null, Category.ENTITY,
                Severity.HIGH,
                "Disconnecting a player has to happen on the region that owns them",
                "Run it through the player's EntityScheduler"));

        // ================================================================
        // Worlds and chunks
        // ================================================================
        rules.add(new UnsafeApi(WORLD, "getChunkAt", null, Category.CHUNK,
                Severity.HIGH,
                "A synchronous chunk load blocks the region thread and stalls its ticking",
                "Use getChunkAtAsync(...)"));
        for (String method : List.of("loadChunk", "unloadChunk")) {
            rules.add(new UnsafeApi(WORLD, method, null, Category.CHUNK,
                    Severity.HIGH,
                    "Synchronous chunk operations block the region thread",
                    "Use the asynchronous chunk API"));
        }
        for (String method : List.of("load", "unload")) {
            rules.add(new UnsafeApi(CHUNK, method, null, Category.CHUNK,
                    Severity.HIGH,
                    "Synchronous chunk operations block the region thread",
                    "Use the asynchronous chunk API"));
        }

        String worldWriteReason =
                "Writing to a world is only allowed from the thread that owns the region "
                        + "containing the target location";
        String worldWriteRemedy =
                "Perform the write inside RegionScheduler.execute(plugin, location, ...)";
        for (String method : List.of("spawn", "spawnEntity", "dropItem", "dropItemNaturally",
                "strikeLightning", "strikeLightningEffect", "createExplosion", "generateTree")) {
            rules.add(new UnsafeApi(WORLD, method, null, Category.WORLD,
                    Severity.HIGH, worldWriteReason, worldWriteRemedy));
        }
        rules.add(new UnsafeApi(WORLD, "getBlockAt", null, Category.WORLD,
                Severity.MEDIUM,
                "Reading a block from outside its owning region can return inconsistent state",
                "Read from inside the owning region, or fetch the value through RegionScheduler"));

        // A world-wide entity lookup walks entities owned by every region in the
        // world at once, so it races with those regions ticking — the same hazard
        // as Entity.getNearbyEntities, one scope wider.
        String worldEntityScanReason =
                "A world-wide entity lookup traverses entities owned by every region "
                        + "in the world, racing with those regions as they tick";
        String worldEntityScanRemedy =
                "Keep the query inside a single region, or gather the result on each "
                        + "region's own thread through RegionScheduler";
        for (String method : List.of("getEntities", "getLivingEntities", "getNearbyEntities")) {
            rules.add(new UnsafeApi(WORLD, method, null, Category.ENTITY,
                    Severity.HIGH, worldEntityScanReason, worldEntityScanRemedy));
        }

        // ================================================================
        // Inventories
        // ================================================================
        rules.add(new UnsafeApi(HUMAN_ENTITY, "openInventory", null, Category.INVENTORY,
                Severity.HIGH,
                "Opening an inventory is only allowed from the region that owns the player",
                "Call player.getScheduler().run(plugin, task -> ..., null)"));
        rules.add(new UnsafeApi(HUMAN_ENTITY, "closeInventory", null, Category.INVENTORY,
                Severity.HIGH,
                "Closing an inventory is only allowed from the region that owns the player",
                "Call player.getScheduler().run(plugin, task -> ..., null)"));
        for (String method : List.of("setItem", "addItem", "removeItem", "clear")) {
            rules.add(new UnsafeApi(INVENTORY, method, null, Category.INVENTORY,
                    Severity.MEDIUM,
                    "Mutating an inventory is only safe from the region that owns its holder",
                    "Run it through the holding player's EntityScheduler"));
        }

        // ================================================================
        // Server-wide — operations that span regions
        // ================================================================
        // Covered on both owners, since plugins reach the server through the
        // Bukkit facade and through the Server interface interchangeably.
        for (String serverOwner : List.of(BUKKIT, SERVER)) {
            rules.add(new UnsafeApi(serverOwner, "getOnlinePlayers", null, Category.SERVER,
                    Severity.INFO,
                    "Online players are spread across regions, so iterating the returned "
                            + "collection and acting on each one crosses region boundaries",
                    "Dispatch the work to each player's EntityScheduler individually"));
            rules.add(new UnsafeApi(serverOwner, "shutdown", null, Category.SERVER,
                    Severity.MEDIUM,
                    "Shutting the server down has to happen on the global region",
                    "Run it through GlobalRegionScheduler"));
            rules.add(new UnsafeApi(serverOwner, "dispatchCommand", null, Category.SERVER,
                    Severity.MEDIUM,
                    "Dispatching a command runs its handler on the calling thread, and most "
                            + "commands touch worlds or entities that belong to a specific region",
                    "Dispatch it from the region that owns what the command affects, "
                            + "for example inside RegionScheduler.execute(...)"));
        }

        // ================================================================
        // Reflection — make the limits of static analysis explicit
        // ================================================================
        String reflectionReason =
                "Static analysis cannot follow calls made through reflection, so "
                        + "Folia-incompatible code may be hidden behind this one";
        String reflectionRemedy =
                "Complement this report with runtime verification (foliacode agent)";
        rules.add(new UnsafeApi("java/lang/Class", "forName", null, Category.REFLECTION,
                Severity.INFO, reflectionReason, reflectionRemedy));
        for (String method : List.of("getDeclaredMethod", "getMethod")) {
            rules.add(new UnsafeApi("java/lang/Class", method, null, Category.REFLECTION,
                    Severity.INFO, reflectionReason, reflectionRemedy));
        }
        rules.add(new UnsafeApi("java/lang/reflect/Method", "invoke", null, Category.REFLECTION,
                Severity.INFO, reflectionReason, reflectionRemedy));

        return Collections.unmodifiableList(rules);
    }
}
