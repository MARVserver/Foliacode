package dev.marv.foliacode.analysis;

import dev.marv.foliacode.model.CallKind;
import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.rules.TypeHierarchy;
import dev.marv.foliacode.rules.UnsafeApiRegistry;
import dev.marv.foliacode.testsupport.BukkitStubs;
import dev.marv.foliacode.testsupport.JavaSourceCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies what {@link ClassScanner} detects, using bytecode javac actually
 * produced.
 *
 * <p>The two cases below are the ones pasta missed structurally, so each gets
 * its own test to keep them from regressing:
 * <ul>
 *   <li>method references (via invokedynamic)</li>
 *   <li>calls made through a subtype</li>
 * </ul>
 * </p>
 */
class ClassScannerTest {

    @Test
    @DisplayName("detects an ordinary method call")
    void detectsDirectCall(@TempDir Path tempDir) throws IOException {
        List<Finding> findings = scan(tempDir, "example.Direct", """
                package example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Direct {
                    public void clear(Block block) {
                        block.setType(Material.AIR);
                    }
                }
                """);

        assertEquals(1, findings.size(), "the setType call should produce exactly one finding");
        Finding finding = findings.get(0);
        assertEquals("Block.setType", finding.api().displayName());
        assertEquals(CallKind.DIRECT_CALL, finding.callKind());
        assertEquals(Severity.HIGH, finding.severity());
        assertEquals("example.Direct", finding.className());
        assertEquals("clear", finding.methodName());
    }

    @Test
    @DisplayName("detects a call made through a method reference (invokedynamic) — a case pasta missed")
    void detectsMethodReference(@TempDir Path tempDir) throws IOException {
        List<Finding> findings = scan(tempDir, "example.Refs", """
                package example;
                import java.util.List;
                import org.bukkit.block.Block;
                public class Refs {
                    public void breakAll(List<Block> blocks) {
                        blocks.forEach(Block::breakNaturally);
                    }
                }
                """);

        List<Finding> viaReference = findings.stream()
                .filter(f -> f.callKind() == CallKind.METHOD_REFERENCE)
                .toList();

        assertEquals(1, viaReference.size(),
                "Block::breakNaturally should be detected as a method reference."
                        + " Actual findings: " + findings);
        assertEquals("Block.breakNaturally", viaReference.get(0).api().displayName());
    }

    @Test
    @DisplayName("detects a call inside a lambda body")
    void detectsCallInsideLambdaBody(@TempDir Path tempDir) throws IOException {
        List<Finding> findings = scan(tempDir, "example.Lambdas", """
                package example;
                import java.util.List;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Lambdas {
                    public void clearAll(List<Block> blocks) {
                        blocks.forEach(block -> block.setType(Material.AIR));
                    }
                }
                """);

        assertEquals(1, findings.size(),
                "a lambda body compiles to a synthetic method, which must be scanned too");
        assertEquals("Block.setType", findings.get(0).api().displayName());
        assertEquals(CallKind.DIRECT_CALL, findings.get(0).callKind());
    }

    @Test
    @DisplayName("detects a call made through a subtype — a case pasta missed")
    void detectsCallThroughSubtype(@TempDir Path tempDir) throws IOException {
        List<Finding> findings = scan(tempDir, "example.Subtypes", """
                package example;
                import org.bukkit.Location;
                import org.bukkit.entity.Player;
                public class Subtypes {
                    public void move(Player player, Location location) {
                        player.teleport(location);
                    }
                }
                """);

        assertEquals(1, findings.size(),
                "the call owner is Player, but the Entity.teleport rule should still match");
        Finding finding = findings.get(0);
        assertEquals("Entity.teleport", finding.api().displayName());
        assertEquals("org/bukkit/entity/Player", finding.calleeOwner());
        assertTrue(finding.isViaSubtype(), "it should be recorded as reached through a subtype");
    }

    @Test
    @DisplayName("finds nothing in a clean class")
    void ignoresCleanClass(@TempDir Path tempDir) throws IOException {
        List<Finding> findings = scan(tempDir, "example.Clean", """
                package example;
                public class Clean {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        assertTrue(findings.isEmpty(), "false positives are unacceptable. Actual findings: " + findings);
    }

    @Test
    @DisplayName("records the line number")
    void recordsLineNumber(@TempDir Path tempDir) throws IOException {
        List<Finding> findings = scan(tempDir, "example.Lines", """
                package example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Lines {
                    public void clear(Block block) {
                        block.setType(Material.AIR);
                    }
                }
                """);

        assertEquals(1, findings.size());
        Finding finding = findings.get(0);
        assertTrue(finding.hasLineNumber(), "the line number should be available");
        assertEquals(6, finding.lineNumber(), "setType is on line 6 of the source");
    }

    @Test
    @DisplayName("separates the synchronous scheduler (CRITICAL) from the async one (MEDIUM)")
    void distinguishesSyncAndAsyncScheduler(@TempDir Path tempDir) throws IOException {
        List<Finding> findings = scan(tempDir, "example.Schedulers", """
                package example;
                import org.bukkit.Bukkit;
                import org.bukkit.plugin.Plugin;
                public class Schedulers {
                    public void schedule(Plugin plugin, Runnable task) {
                        Bukkit.getScheduler().runTask(plugin, task);
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
                    }
                }
                """);

        assertEquals(2, findings.size(), "Actual findings: " + findings);
        assertTrue(findings.stream().anyMatch(f ->
                        f.api().displayName().equals("BukkitScheduler.runTask")
                                && f.severity() == Severity.CRITICAL),
                "the synchronous runTask should be CRITICAL");
        assertTrue(findings.stream().anyMatch(f ->
                        f.api().displayName().equals("BukkitScheduler.runTaskAsynchronously")
                                && f.severity() == Severity.MEDIUM),
                "the async variant works on Folia, so it should stay at MEDIUM");
    }

    @Test
    @DisplayName("does not flag viaSubtype when the call is on the declaring type")
    void directOwnerIsNotMarkedAsSubtype(@TempDir Path tempDir) throws IOException {
        List<Finding> findings = scan(tempDir, "example.Exact", """
                package example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Exact {
                    public void clear(Block block) {
                        block.setType(Material.AIR);
                    }
                }
                """);

        assertEquals(1, findings.size());
        assertFalse(findings.get(0).isViaSubtype());
    }

    /**
     * Compiles a source and scans the resulting class.
     *
     * @param tempDir   the working directory
     * @param className fully-qualified name of the class to scan
     * @param source    its source
     * @return the findings
     * @throws IOException if compilation or reading fails
     */
    private static List<Finding> scan(Path tempDir, String className, String source) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.with(className, source));
        byte[] bytes = JavaSourceCompiler.readClass(classesDir, className);

        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);

        ClassScanner scanner = new ClassScanner(new UnsafeApiRegistry(), new TypeHierarchy());
        return scanner.scan(node);
    }
}
