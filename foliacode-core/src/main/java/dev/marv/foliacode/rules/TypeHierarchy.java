package dev.marv.foliacode.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds inheritance relationships so that calls through subtypes can be
 * resolved.
 *
 * <p>In bytecode the owner of a call is the static type known at compile
 * time. A call to {@code Player#teleport} is therefore recorded with owner
 * {@code org/bukkit/entity/Player} and never matches
 * {@code org/bukkit/entity/Entity} by name. pasta compared owners exactly,
 * which is why it missed every call made through a subtype.</p>
 *
 * <p>This class combines two sources of knowledge:
 * <ul>
 *   <li>a built-in slice of the Bukkit API hierarchy (known ahead of time)</li>
 *   <li>the hierarchy learned from the classes inside the JAR being analysed</li>
 * </ul>
 * Together they make subtype resolution work without paper-api on the
 * classpath.</p>
 */
public final class TypeHierarchy {

    /** Internal name to direct supertypes (superclass plus all interfaces). */
    private final Map<String, List<String>> directSupertypes = new HashMap<>();

    public TypeHierarchy() {
        seedBukkitTypes();
    }

    /**
     * Learns an inheritance relationship from a class being analysed.
     *
     * @param internalName internal name of the class
     * @param superName    internal name of the superclass; may be {@code null}
     * @param interfaces   internal names of implemented interfaces; may be {@code null}
     */
    public void learn(String internalName, String superName, String[] interfaces) {
        if (internalName == null) {
            return;
        }
        List<String> supers = new ArrayList<>();
        if (superName != null && !"java/lang/Object".equals(superName)) {
            supers.add(superName);
        }
        if (interfaces != null) {
            supers.addAll(Arrays.asList(interfaces));
        }
        if (supers.isEmpty()) {
            directSupertypes.putIfAbsent(internalName, List.of());
            return;
        }
        directSupertypes.merge(internalName, List.copyOf(supers), (existing, added) -> {
            Set<String> merged = new java.util.LinkedHashSet<>(existing);
            merged.addAll(added);
            return List.copyOf(merged);
        });
    }

    /**
     * Tests whether {@code candidate} is {@code target} or a subtype of it.
     *
     * @param candidate internal name to test
     * @param target    internal name to test against
     * @return true if it is the same type or a subtype
     */
    public boolean isSubtypeOf(String candidate, String target) {
        if (candidate == null || target == null) {
            return false;
        }
        if (candidate.equals(target)) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(candidate);
        visited.add(candidate);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : directSupertypes.getOrDefault(current, List.of())) {
                if (parent.equals(target)) {
                    return true;
                }
                if (visited.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return false;
    }

    /** Number of known types, including the built-in ones. */
    public int knownTypeCount() {
        return directSupertypes.size();
    }

    /**
     * Registers the built-in slice of the Bukkit API hierarchy.
     *
     * <p>This is deliberately not exhaustive: it covers the type families that
     * own APIs which break on Folia. Anything missing here is picked up by
     * learning from the JAR under analysis.</p>
     */
    private void seedBukkitTypes() {
        // --- Entities: needed to resolve subtypes for Entity.teleport / remove ---
        register("org/bukkit/entity/LivingEntity", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/Damageable", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/Attributable", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/HumanEntity", "org/bukkit/entity/LivingEntity");
        register("org/bukkit/entity/Player", "org/bukkit/entity/HumanEntity");
        register("org/bukkit/entity/Mob", "org/bukkit/entity/LivingEntity");
        register("org/bukkit/entity/Creature", "org/bukkit/entity/Mob");
        register("org/bukkit/entity/Monster", "org/bukkit/entity/Creature");
        register("org/bukkit/entity/Animals", "org/bukkit/entity/Creature");
        register("org/bukkit/entity/Ageable", "org/bukkit/entity/Creature");
        register("org/bukkit/entity/Tameable", "org/bukkit/entity/Animals");
        register("org/bukkit/entity/Villager", "org/bukkit/entity/AbstractVillager");
        register("org/bukkit/entity/AbstractVillager", "org/bukkit/entity/Merchant",
                "org/bukkit/entity/Creature");
        register("org/bukkit/entity/Item", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/Projectile", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/Arrow", "org/bukkit/entity/AbstractArrow");
        register("org/bukkit/entity/AbstractArrow", "org/bukkit/entity/Projectile");
        register("org/bukkit/entity/ArmorStand", "org/bukkit/entity/LivingEntity");
        register("org/bukkit/entity/Vehicle", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/Minecart", "org/bukkit/entity/Vehicle");
        register("org/bukkit/entity/Boat", "org/bukkit/entity/Vehicle");
        register("org/bukkit/entity/FallingBlock", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/LightningStrike", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/Explosive", "org/bukkit/entity/Entity");
        register("org/bukkit/entity/TNTPrimed", "org/bukkit/entity/Explosive");

        // --- Inventories ---
        register("org/bukkit/inventory/PlayerInventory", "org/bukkit/inventory/Inventory");
        register("org/bukkit/inventory/DoubleChestInventory", "org/bukkit/inventory/Inventory");
        register("org/bukkit/inventory/AnvilInventory", "org/bukkit/inventory/Inventory");
        register("org/bukkit/inventory/FurnaceInventory", "org/bukkit/inventory/Inventory");
        register("org/bukkit/inventory/CraftingInventory", "org/bukkit/inventory/Inventory");

        // --- Blocks ---
        register("org/bukkit/block/BlockState", "org/bukkit/metadata/Metadatable");
        register("org/bukkit/block/Chest", "org/bukkit/block/Container");
        register("org/bukkit/block/Container", "org/bukkit/block/TileState",
                "org/bukkit/inventory/InventoryHolder");
        register("org/bukkit/block/TileState", "org/bukkit/block/BlockState");
        register("org/bukkit/block/Sign", "org/bukkit/block/TileState");

        // --- Command senders: a Player is also a CommandSender ---
        register("org/bukkit/entity/Player", "org/bukkit/command/CommandSender");

        // --- Schedulers ---
        register("org/bukkit/scheduler/BukkitRunnable", "java/lang/Runnable");
    }

    /**
     * Registers one built-in relationship.
     *
     * @param type   internal name of the type
     * @param supers internal names of its supertypes
     */
    private void register(String type, String... supers) {
        learn(type, null, supers);
    }
}
