package dev.marv.foliacode.rules;

import dev.marv.foliacode.model.Category;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.UnsafeApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies rule matching in {@link UnsafeApiRegistry}.
 */
class UnsafeApiRegistryTest {

    private final UnsafeApiRegistry registry = new UnsafeApiRegistry();
    private final TypeHierarchy hierarchy = new TypeHierarchy();

    @Test
    @DisplayName("matches the synchronous scheduler as CRITICAL")
    void matchesSyncScheduler() {
        Optional<UnsafeApi> match = registry.match(
                "org/bukkit/scheduler/BukkitScheduler", "runTask", "()V", hierarchy);

        assertTrue(match.isPresent());
        assertEquals(Severity.CRITICAL, match.get().severity());
        assertEquals(Category.SCHEDULER, match.get().category());
    }

    @Test
    @DisplayName("matches through a subtype")
    void matchesThroughSubtype() {
        Optional<UnsafeApi> match = registry.match(
                "org/bukkit/entity/Player", "teleport", "(Lorg/bukkit/Location;)Z", hierarchy);

        assertTrue(match.isPresent(), "Player.teleport should match the Entity.teleport rule");
        assertEquals("org/bukkit/entity/Entity", match.get().owner());
    }

    @Test
    @DisplayName("does not match unrelated calls")
    void doesNotMatchUnrelatedCalls() {
        assertTrue(registry.match("java/util/List", "add", "(Ljava/lang/Object;)Z", hierarchy).isEmpty());
        assertTrue(registry.match("java/lang/String", "length", "()I", hierarchy).isEmpty());
        assertTrue(registry.match("org/bukkit/block/Block", "getType", "()V", hierarchy).isEmpty(),
                "read-only methods are deliberately not covered by any rule");
    }

    @Test
    @DisplayName("a wildcard rule matches every method")
    void wildcardRuleMatchesAnyMethod() {
        Optional<UnsafeApi> match = registry.match(
                "org/bukkit/WorldCreator", "environment", "()V", hierarchy);

        assertTrue(match.isPresent(), "every WorldCreator method should be covered");
        assertEquals(Category.WORLDGEN, match.get().category());
    }

    @Test
    @DisplayName("returns the most serious rule when several match")
    void returnsMostSevereMatch() {
        List<UnsafeApi> rules = List.of(
                new UnsafeApi("org/example/Target", "act", null, Category.WORLD,
                        Severity.MEDIUM, "the milder reason", "the milder remedy"),
                new UnsafeApi("org/example/Target", "act", null, Category.WORLD,
                        Severity.CRITICAL, "the graver reason", "the graver remedy"));
        UnsafeApiRegistry custom = new UnsafeApiRegistry(rules);

        Optional<UnsafeApi> match = custom.match("org/example/Target", "act", "()V", hierarchy);

        assertTrue(match.isPresent());
        assertEquals(Severity.CRITICAL, match.get().severity(),
                "the more serious rule should win");
    }

    @Test
    @DisplayName("a rule with a descriptor also matches on the descriptor")
    void respectsDescriptorConstraint() {
        List<UnsafeApi> rules = List.of(
                new UnsafeApi("org/example/Target", "act", "(I)V", Category.WORLD,
                        Severity.HIGH, "a reason", "a remedy"));
        UnsafeApiRegistry custom = new UnsafeApiRegistry(rules);

        assertTrue(custom.match("org/example/Target", "act", "(I)V", hierarchy).isPresent());
        assertTrue(custom.match("org/example/Target", "act", "(J)V", hierarchy).isEmpty(),
                "a different descriptor should not match");
    }

    @Test
    @DisplayName("handles null input safely")
    void handlesNullSafely() {
        assertTrue(registry.match(null, "runTask", "()V", hierarchy).isEmpty());
        assertTrue(registry.match("org/bukkit/scheduler/BukkitScheduler", null, "()V", hierarchy).isEmpty());
    }

    @Test
    @DisplayName("no default rule repeats an owner+method+descriptor")
    void defaultRulesHaveNoExactDuplicates() {
        List<UnsafeApi> rules = UnsafeApiRegistry.defaultRules();
        long distinct = rules.stream()
                .map(r -> r.owner() + "#" + r.methodName() + "#" + r.descriptor())
                .distinct()
                .count();

        assertEquals(rules.size(), distinct,
                "two rules on one signature make it impossible to tell which one was applied");
    }

    @Test
    @DisplayName("every default rule carries a reason and a remedy")
    void defaultRulesAreDocumented() {
        for (UnsafeApi rule : UnsafeApiRegistry.defaultRules()) {
            assertFalse(rule.reason().isBlank(), rule.displayName() + " has no reason");
            assertFalse(rule.remedy().isBlank(), rule.displayName() + " has no remedy");
        }
    }
}
