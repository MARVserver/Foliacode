package dev.marv.foliacode.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies type resolution in {@link TypeHierarchy}.
 */
class TypeHierarchyTest {

    @Test
    @DisplayName("a type is a subtype of itself")
    void selfIsSubtype() {
        TypeHierarchy hierarchy = new TypeHierarchy();
        assertTrue(hierarchy.isSubtypeOf("org/bukkit/entity/Entity", "org/bukkit/entity/Entity"));
    }

    @Test
    @DisplayName("built-in knowledge resolves Player as a subtype of Entity")
    void resolvesBuiltInBukkitHierarchy() {
        TypeHierarchy hierarchy = new TypeHierarchy();
        assertTrue(hierarchy.isSubtypeOf("org/bukkit/entity/Player", "org/bukkit/entity/Entity"),
                "the path Player -> HumanEntity -> LivingEntity -> Entity should resolve");
        assertTrue(hierarchy.isSubtypeOf("org/bukkit/entity/Player", "org/bukkit/entity/LivingEntity"));
        assertTrue(hierarchy.isSubtypeOf("org/bukkit/inventory/PlayerInventory",
                "org/bukkit/inventory/Inventory"));
    }

    @Test
    @DisplayName("unrelated types are not subtypes")
    void unrelatedTypesAreNotSubtypes() {
        TypeHierarchy hierarchy = new TypeHierarchy();
        assertFalse(hierarchy.isSubtypeOf("org/bukkit/block/Block", "org/bukkit/entity/Entity"));
        assertFalse(hierarchy.isSubtypeOf("java/lang/String", "org/bukkit/entity/Entity"));
    }

    @Test
    @DisplayName("learns inheritance from the classes being analysed")
    void learnsHierarchyFromScannedClasses() {
        TypeHierarchy hierarchy = new TypeHierarchy();
        hierarchy.learn("com/example/CustomBlock", "java/lang/Object",
                new String[]{"org/bukkit/block/Block"});

        assertTrue(hierarchy.isSubtypeOf("com/example/CustomBlock", "org/bukkit/block/Block"),
                "an implementation defined inside the JAR should resolve as a Block subtype");
    }

    @Test
    @DisplayName("follows inheritance across several levels")
    void resolvesTransitiveHierarchy() {
        TypeHierarchy hierarchy = new TypeHierarchy();
        hierarchy.learn("com/example/Base", "java/lang/Object",
                new String[]{"org/bukkit/entity/LivingEntity"});
        hierarchy.learn("com/example/Middle", "com/example/Base", null);
        hierarchy.learn("com/example/Leaf", "com/example/Middle", null);

        assertTrue(hierarchy.isSubtypeOf("com/example/Leaf", "org/bukkit/entity/Entity"),
                "the path Leaf -> Middle -> Base -> LivingEntity -> Entity should resolve");
    }

    @Test
    @DisplayName("does not loop forever on cyclic inheritance data")
    void survivesCyclicHierarchy() {
        TypeHierarchy hierarchy = new TypeHierarchy();
        hierarchy.learn("com/example/A", "com/example/B", null);
        hierarchy.learn("com/example/B", "com/example/A", null);

        // A broken or deliberately crafted JAR must not stall the analysis
        assertFalse(hierarchy.isSubtypeOf("com/example/A", "org/bukkit/entity/Entity"));
    }

    @Test
    @DisplayName("handles null safely")
    void handlesNullSafely() {
        TypeHierarchy hierarchy = new TypeHierarchy();
        assertFalse(hierarchy.isSubtypeOf(null, "org/bukkit/entity/Entity"));
        assertFalse(hierarchy.isSubtypeOf("org/bukkit/entity/Player", null));
    }
}
